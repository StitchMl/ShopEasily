package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleShoppingBasketTest {
    private val requested = listOf(
        "Sale lavastoviglie", "Quasar vetri", "Birra", "Scottex", "Latte", "acqua", "Pappa gatti/cane",
    )
    private val budget = Store(1, "Spesa Budget", StoreChannel.PHYSICAL, 41.90, 12.49, 900)
    private val ecoFair = Store(2, "Emporio Eco Equo", StoreChannel.PHYSICAL, 41.91, 12.50, 1_100)
    private val catalog = requested.flatMapIndexed { index, name ->
        listOf(
            item(budget, name, 1.0 + index, quality = 3.0, ecological = false, fairTrade = false),
            item(ecoFair, name, 1.35 + index, quality = 4.8, ecological = true, fairTrade = true),
        )
    }

    @Test
    fun cheapestPlanCoversTheWholeExampleList() {
        val plan = BasketOptimizer.optimize(requested, catalog, goal = BasketGoal.CHEAPEST).first()

        assertEquals(requested.size, plan.assignments.size)
        assertEquals("Spesa Budget", plan.stores.single().name)
        assertEquals(28.0, plan.productsTotal, 0.001)
    }

    @Test
    fun ecologicalAndFairTradePlanCoversTheWholeExampleList() {
        val plan = BasketOptimizer.optimize(requested, catalog, goal = BasketGoal.BALANCED).first()

        assertEquals(requested.size, plan.assignments.size)
        assertEquals(requested.size, plan.ecologicalItems)
        assertEquals(requested.size, plan.fairTradeItems)
        assertTrue(plan.averageQuality >= 4.8)
        assertEquals("Emporio Eco Equo", plan.stores.single().name)
    }

    private fun item(
        store: Store,
        name: String,
        price: Double,
        quality: Double,
        ecological: Boolean,
        fairTrade: Boolean,
    ) = CatalogPrice(
        store = store,
        productName = name,
        aliases = aliases(name),
        price = price,
        promotional = true,
        qualityScore = quality,
        ecological = ecological,
        fairTrade = fairTrade,
    )

    private fun aliases(name: String): Set<String> = when (name) {
        "Pappa gatti/cane" -> setOf("pappa gatti/cane", "pappa gatti", "pappa cane", "cibo animali")
        else -> setOf(name.lowercase())
    }
}
