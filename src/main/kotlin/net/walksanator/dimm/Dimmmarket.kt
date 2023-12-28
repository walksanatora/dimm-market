package net.walksanator.dimm

import com.google.gson.GsonBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import dev.emi.emi.api.EmiApi
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.item.v1.FabricItemSettings
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException
import java.util.function.Supplier


object Dimmmarket : ModInitializer {
    private val logger = LoggerFactory.getLogger("dimm-market")

	override fun onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Hello Fabric world!")

		CommandRegistrationCallback.EVENT.register {listener,_,_ ->
			run {
				listener.run {
					register(
								literal<ServerCommandSource?>("dmarket-ss")
									.then(literal<ServerCommandSource?>("dump-recipes")
										.executes {
											val manager = EmiApi.getRecipeManager();
											val gson = GsonBuilder().setPrettyPrinting().create()
											val ser = gson.toJson(manager.recipes.map {JsonRecipe.fromRecipe(it)})
											val jsonFilePath = FabricLoader.getInstance().gameDir.resolve("srdump.json")
											try {
												BufferedWriter(FileWriter(jsonFilePath.toFile())).use { writer ->
													writer.write(ser)
													it.source.sendFeedback({Text.literal("Server Side Dump")},true)
												}
											} catch (e: IOException) {
												e.printStackTrace()
											}
											1
										}
									)
							)
				}
			}
		}
	}
}