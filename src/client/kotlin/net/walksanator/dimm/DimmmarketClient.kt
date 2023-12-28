package net.walksanator.dimm

import com.google.gson.GsonBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import dev.emi.emi.api.EmiApi
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.text.Text
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException


object DimmmarketClient : ClientModInitializer {
	override fun onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		ClientCommandRegistrationCallback.EVENT.register {listener,_ ->
			run {
				listener.register(
					literal<FabricClientCommandSource?>("dmarket")
						.then(literal<FabricClientCommandSource?>("dump-recipes")
							.executes {
								val manager = EmiApi.getRecipeManager();
								val gson = GsonBuilder().setPrettyPrinting().create()
								val ser = gson.toJson(manager.recipes.map {JsonRecipe.fromRecipe(it)})
								val jsonFilePath = FabricLoader.getInstance().gameDir.resolve("rdump.json")
								try {
									BufferedWriter(FileWriter(jsonFilePath.toFile())).use { writer ->
										writer.write(ser)
										it.source.sendFeedback(Text.literal("Dumped emi recipes to json rdump.json in instance"))
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