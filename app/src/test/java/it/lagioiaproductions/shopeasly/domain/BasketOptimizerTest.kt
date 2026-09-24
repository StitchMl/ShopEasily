package it.lagioiaproductions.shopeasly.domain

import it.lagioiaproductions.shopeasly.data.repository.FakeCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasketOptimizerTest {
    private val catalog = FakeCatalogRepository().catalog

    @Test
    fun optimizerUsesRegularPricesAndPromotions() {
        val plans = BasketOptimizer.optimize(listOf("Latte", "Pasta", "Pomodori"), catalog)

        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { it.assignments.size == 3 })
        assertTrue(plans.flatMap { it.assignments }.any { !it.catalogItem.promotional })
    }

    @Test
    fun optimizerIncludesOnlineStoresAndDeliveryCosts() {
        val plans = BasketOptimizer.optimize(listOf("Latte", "Pasta", "Pomodori"), catalog)

        assertTrue(plans.any { plan -> plan.stores.any { it.latitude == null } })
        assertTrue(plans.filter { it.stores.any { store -> store.latitude == null } }.all { it.serviceCosts > 0 })
    }

    @Test
    fun unknownProductsProduceNoPlan() {
        val plans = BasketOptimizer.optimize(listOf("Prodotto inesistente"), catalog)

        assertEquals(emptyList<Any>(), plans)
    }
}
