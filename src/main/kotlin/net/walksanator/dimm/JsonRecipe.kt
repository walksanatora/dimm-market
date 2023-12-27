package net.walksanator.dimm

import dev.emi.emi.api.recipe.EmiRecipe
import net.minecraft.util.Identifier

class JsonRecipe(val id: Identifier, val type: Identifier, val input: List<JsonIngredient>, val output: List<JsonIngredient>) {
    companion object {
        fun fromRecipe(recipe: EmiRecipe): JsonRecipe {
            return JsonRecipe(
                recipe.id!!,
                recipe.category.id,
                recipe.inputs.map { it.emiStacks }.flatten().map {
                    JsonIngredient(it.id,it.amount,it.chance)
                },
                recipe.outputs.map {
                    JsonIngredient(it.id, it.amount,it.chance)
                }
            )
        }
    }
}

class JsonIngredient(val id: Identifier, val ammount: Long, val chance: Float)