package net.walksanator.dimm

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resource.ResourceManager
import net.minecraft.util.Identifier
import java.io.InputStreamReader


class ReloadListener : SimpleSynchronousResourceReloadListener {
    val defaultHard = HashMap<Identifier,Int>()
    override fun getFabricId(): Identifier = Identifier("dimm-market", "res")

    override fun reload(manager: ResourceManager) {
        Dimmmarket.logger.info("started reloading EMC resources")
        for ((id,res) in manager.findResources("default_emc") {it.path.endsWith(".json")} ) {
            try {
                Dimmmarket.logger.info("Located Resource at id $id pack ${res.resourcePackName}")
                val values = Dimmmarket.gson_serde.fromJson(InputStreamReader(res.inputStream),HashMap<String,Int>().javaClass)
                val remapped = values.entries.associate { Identifier.tryParse(it.key)!! to it.value }
                defaultHard.putAll(remapped);
            } catch (e: Exception) {
                Dimmmarket.logger.error("Error occurred while loading resource json $id", e)
            }
        }
        Dimmmarket.logger.info("finished reloading EMC resources")
    }
}