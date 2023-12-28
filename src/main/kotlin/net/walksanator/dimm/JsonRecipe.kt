package net.walksanator.dimm

import dev.emi.emi.api.recipe.EmiRecipe
import dev.emi.emi.api.stack.ListEmiIngredient
import dev.emi.emi.api.stack.TagEmiIngredient
import net.minecraft.util.Identifier

class JsonRecipe(val id: Identifier, val type: Identifier, val input: List<JsonIngredient>, val output: List<JsonIngredient>) {
    companion object {
        fun fromRecipe(recipe: EmiRecipe): JsonRecipe? {
            val ret = JsonRecipe(
                recipe.id!!,
                recipe.category.id,
                recipe.inputs.map {
                    val stacks = it.emiStacks
                    if (it is ListEmiIngredient) {
                        return@map stacks
                    } else {
                        return@map listOf(stacks.first())
                    }
                }.flatten().map {
                    JsonIngredient(it.id,it.amount,it.chance)
                },
                recipe.outputs.map {
                    JsonIngredient(it.id, it.amount,it.chance)
                }
            )



            return ret
        }
    }
}

class JsonIngredient(val id: Identifier, val ammount: Long, val chance: Float)