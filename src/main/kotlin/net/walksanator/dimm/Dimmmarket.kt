package net.walksanator.dimm

import com.google.common.base.Optional
import com.google.gson.GsonBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import dev.emi.emi.api.EmiApi
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.command.argument.ArgumentTypes
import net.minecraft.command.argument.IdentifierArgumentType
import net.minecraft.command.argument.ItemStackArgumentType
import net.minecraft.command.argument.RegistryKeyArgumentType
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.resource.ResourceType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.floor


object Dimmmarket : ModInitializer {
    val logger = LoggerFactory.getLogger("dimm-market")
    val gson_serde = GsonBuilder().setPrettyPrinting().setLenient().create()


    private val hard_emc_values_json = FabricLoader.getInstance().configDir.resolve("hard_market_values.json");
    private val soft_emc_values_json = FabricLoader.getInstance().configDir.resolve("market_values.json");
    private val hard_emc_values = HashMap<Identifier, Int>()
    private val emc_values = HashMap<Identifier, EmcValue>()

    private val recipe_lookup = HashMap<Identifier, JsonRecipe>()
    private val reverse_recipe_lookup = HashMap<Identifier, ArrayList<Identifier>>()

    private val tag_lookup = HashMap<Identifier, ArrayList<Identifier>>()
    private val reverse_tag_lookup = HashMap<Identifier, ArrayList<Identifier>>()

    private val items_needing_emc = ArrayList<Identifier>()

    private var needs_emc_gen = false;
    private val resources = ReloadListener();
    override fun onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        logger.info("Hello Fabric world!")

        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(resources);

        ServerLifecycleEvents.SERVER_STARTED.register {
            run {
                reloadEmcFiles()
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            run {
                writeValuesToJson()
            }
        }

        ServerTickEvents.END_SERVER_TICK.register {
            run {
                if (needs_emc_gen) {
                    needs_emc_gen = false;
                    logger.info("late derive!")
                    deriveEmcValues()
                }
            }
        }

        CommandRegistrationCallback.EVENT.register { listener, ra, _ ->
            run {
                listener.run {
                    register(literal<ServerCommandSource?>("dmarket")
                        .then(literal<ServerCommandSource?>("dump-recipes")
                            .executes {
                                val manager = EmiApi.getRecipeManager();
                                val gson = GsonBuilder().setPrettyPrinting().create()
                                val ser = gson.toJson(manager.recipes.map { JsonRecipe.fromRecipe(it) })
                                val jsonFilePath = FabricLoader.getInstance().gameDir.resolve("srdump.json")
                                try {
                                    BufferedWriter(FileWriter(jsonFilePath.toFile())).use { writer ->
                                        writer.write(ser)
                                        it.source.sendFeedback({ Text.literal("Server Side recipe dumping completed") }, false)
                                    }
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                                1
                            }
                        )
                        .then(literal<ServerCommandSource?>("emc")
                            .then(literal<ServerCommandSource?>("reload")
                                .executes {
                                    Files.deleteIfExists(soft_emc_values_json)
                                    deriveEmcValues()
                                    it.source.sendFeedback({Text.literal("reloaded soft values from hard values")},true)
                                    1
                                }
                            ).then(literal<ServerCommandSource?>("reset")
                                .executes {
                                    Files.deleteIfExists(soft_emc_values_json)
                                    Files.deleteIfExists(hard_emc_values_json)
                                    deriveEmcValues()
                                    it.source.sendFeedback({Text.literal("reloaded all values from datapacks")},true)
                                    1
                                }
                            ).then(literal<ServerCommandSource?>("save")
                                .executes {
                                    writeValuesToJson()
                                    it.source.sendFeedback({Text.literal("written to config files early")},false)
                                    1
                                }
                            ).then(literal("set-emc")
                                .then(argument("item",RegistryKeyArgumentType.registryKey(Registries.ITEM.key))
                                    .executes {
                                        Registries.ITEM.
                                        itemid.item
                                        1
                                    }
                                )
                            )
                        )
                    )
                }
            }
        }
    }

    private fun deriveEmcValues() {
        val manager = EmiApi.getRecipeManager();
        if (manager.recipes.isEmpty()) {
            needs_emc_gen = true //there is no recipes registered yet so emc_gen is not setup.
            return
        }
        val recipes = manager.recipes.map { JsonRecipe.fromRecipe(it)!! }
        logger.info("deriving EMC")
        logger.info("building json recipes")
        val part = recipes.asSequence().filter { it.type != Identifier.tryParse("emi:fuel")!! }
            .filter { it.type != Identifier.tryParse("emi:composting")!! }
            .filter { it.type != Identifier.tryParse("emi:world_interaction")!! }
            .filter { it.type != Identifier.tryParse("emi:anvil_repairing")!! }
            .filter { it.type != Identifier.tryParse("emi:grinding")!! }
            .filter { it.type.namespace != "emi_loot" }
            .partition { it.type != Identifier.tryParse("emi:tag")!! }
        recipe_lookup.clear()
        reverse_recipe_lookup.clear();
        val items = ArrayList<Identifier>();
        logger.info("building recipe lookups")
        for (recipe in part.first) {
            recipe_lookup[recipe.id] = recipe
            for (output in recipe.output) {
                reverse_recipe_lookup.getOrPut(output.id) { ArrayList() }.add(recipe.id)
            }
            val missingItems =
                (recipe.input.map { it.id } + recipe.output.map { it.id }).toSortedSet().filter { !items.contains(it) }
            items.addAll(missingItems)
        }
        logger.info("building tag lookup")
        val filteredTags =
            part.second.asSequence().filter { it.id != Identifier.tryParse("emi:/tag/item/minecraft/axes")!! }
                .filter { it.id != Identifier.tryParse("emi:/tag/item/minecraft/hoes")!! }
                .filter { it.id != Identifier.tryParse("emi:/tag/item/minecraft/pickaxe")!! }
                .filter { it.id != Identifier.tryParse("emi:/tag/item/minecraft/shovels")!! }
                .filter { it.id != Identifier.tryParse("emi:/tag/item/minecraft/swords")!! }
                .filter { it.id != Identifier.tryParse("emi:/tag/item/minecraft/tools")!! }
        tag_lookup.clear()
        reverse_tag_lookup.clear()
        for (tag in filteredTags) {
            tag_lookup[tag.id] = ArrayList(tag.input.map { it.id })
            for (item in tag.input) {
                reverse_tag_lookup.getOrPut(item.id) { ArrayList() }.add(tag.id)
            }
        }
        logger.info("lookups built")
        items_needing_emc.clear()
        items_needing_emc.addAll(items.filter { emc_values.getOrDefault(it, EmcValue(0, true)).from_tag })
        logger.info("filled list of items needing emc values (or using tags)")
        //insert benny hill theme here
        var metaEmcChanged = 1
        while (metaEmcChanged > 0) {
            metaEmcChanged = 0
            logger.info("performing meta emc loop")
            var emcChanged = 1
            while (emcChanged > 0) {
                emcChanged = 0
                logger.info("performing recipe emc loop")
                items_needing_emc.retainAll { emc_values.getOrDefault(it, EmcValue(0, true)).from_tag }
                for (item in items_needing_emc) {
                    if (!reverse_recipe_lookup.containsKey(item)) {
                        continue
                    } //skip it if it is uncraftable
                    val recipes = reverse_recipe_lookup[item]!!
                    for (rid in recipes) {
                        val recipe = recipe_lookup[rid]!!
                        var accu = 0
                        var failed = false
                        for (ritem in recipe.input) {
                            if (emc_values.containsKey(ritem.id)) {
                                accu += emc_values[ritem.id]!!.emc
                            } else {
                                failed = true;break
                            }
                        }
                        if (failed) {
                            continue
                        }//we failed. skip to next recipe

                        var outputCounts: Long = 0
                        var outputEmc = 0
                        var mySlot = Optional.absent<JsonIngredient>()
                        for (ingr in recipe.output) {
                            if (emc_values.containsKey(ingr.id)) {
                                outputEmc += (emc_values[ingr.id]!!.emc * ingr.ammount).toInt()
                            } else if (ingr.id == item) {
                                if (mySlot.isPresent) {
                                    val old = mySlot.get()
                                    if (old.chance < ingr.chance) {
                                        mySlot = Optional.of(ingr)
                                    }
                                } else {
                                    mySlot = Optional.of(ingr)
                                }
                                outputCounts += ingr.ammount;
                            } else {
                                outputCounts += ingr.ammount;
                            }
                        }
                        if (outputCounts <= 0) {
                            continue; // we either have negative (or zero) items used in output, or the outputs are worth more then the input
                        }
                        accu -= outputEmc;
                        val perOutputCost = accu / outputCounts
                        val weightedPerOutputCost = floor(
                            perOutputCost.toDouble() /
                                    (1f / (if (mySlot.isPresent) {
                                        mySlot.get().chance
                                    } else {
                                        1f
                                    })).toDouble()
                        ).toInt()
                        if (emc_values.containsKey(item)) {
                            if ((weightedPerOutputCost < emc_values[item]!!.emc) || (emc_values[item]!!.from_tag)) {
                                emc_values[item] = EmcValue(weightedPerOutputCost, false)
                                emcChanged += 1
                                metaEmcChanged += 1
                            }
                        } else {
                            emc_values[item] = EmcValue(weightedPerOutputCost, false)
                            emcChanged += 1
                            metaEmcChanged += 1
                        }
                    }
                }
                logger.info("finished recipe emc loop $emcChanged values set")
            }
            emcChanged = 1
            while (emcChanged > 0) {
                emcChanged = 0
                logger.info("performing tag emc loop")
                items_needing_emc.retainAll { emc_values.getOrDefault(it, EmcValue(0, true)).from_tag }
                for (item in items_needing_emc.filter { !emc_values.containsKey(it) }) { //only ones with *no* EMC value
                    if (reverse_tag_lookup.containsKey(item)) {
                        val tags = reverse_tag_lookup[item]!!.map { tag_lookup[it] }
                        val sorted = tags.sortedBy { it!!.size }
                        for (taggedItems in sorted) {
                            var accu = 0
                            var count = 0
                            for (tagged in taggedItems!!) {
                                if (emc_values.containsKey(tagged)) {
                                    accu += emc_values[tagged]!!.emc
                                    count += 1
                                }
                            }
                            if (accu == 0 || count == 0) {
                                continue
                            }
                            logger.info("derived value ${accu / count} for $item via tags")
                            emc_values[item] = EmcValue(accu / count, true)
                            emcChanged += 1
                            metaEmcChanged += 1
                        }
                    }
                }
                logger.info("finished tag emc loop")
            }
        }

    }

    fun writeValuesToJson() {
        val hardJson = gson_serde.toJson(hard_emc_values)
        val softJson = gson_serde.toJson(emc_values)
        Files.newBufferedWriter(hard_emc_values_json, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { it.write(hardJson) }
        Files.newBufferedWriter(soft_emc_values_json, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { it.write(softJson) }
    }

    fun reloadEmcFiles() {
        logger.info("Reloading EMC")
        emc_values.clear()
        if (Files.exists(soft_emc_values_json)) { //calculated SOFT emc values have been fond so we use those
            logger.info("soft emc values located")
            val contents = readJsonFromFile(soft_emc_values_json)
            emc_values.putAll(gson_serde.fromJson(contents, emc_values.javaClass))
        } else if (Files.exists(hard_emc_values_json)) { //HARD emc values located. so we gotta derive them.
            logger.info("hard emc values located")
            val contents = readJsonFromFile(hard_emc_values_json)
            val values = gson_serde.fromJson(contents, HashMap<String, Int>().javaClass)
            val hardRemapped = values.entries.associate { Identifier.tryParse(it.key)!! to it.value }
            hard_emc_values.putAll(hardRemapped);
            val remapped = values.entries.associate { Identifier.tryParse(it.key)!! to EmcValue(it.value, false) }
            emc_values.putAll(remapped)
            deriveEmcValues()
        } else { //we gotta get the default EMC values from the ReloadListener
            logger.info("unnable to find soft or hard values. pulling defaults.")
            hard_emc_values.putAll(resources.defaultHard)
            val remapped = resources.defaultHard.entries.associate { it.key to EmcValue(it.value, false) }
            emc_values.putAll(remapped)
            deriveEmcValues()
        }
    }

    private fun readJsonFromFile(path: Path): String? {
        try {
            val jsonData = Files.readAllBytes(path)
            return String(jsonData)
        } catch (e: IOException) {
            // Handle exceptions (e.g., file not found, IO error)
            e.printStackTrace()
            return null
        }
    }
}