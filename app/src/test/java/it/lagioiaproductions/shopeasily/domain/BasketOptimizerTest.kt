package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.repository.FakeCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import it.lagioiaproductions.shopeasily.data.preferences.VehicleType

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

    @Test
    fun transportProfileChangesTravelCostAndEmissions() {
        val bicycle = BasketOptimizer.optimize(
            listOf("Latte"), catalog,
            transport = TransportProfile(VehicleType.BICYCLE, FuelType.NONE, 0.0, 0.0),
        )
        val dieselVan = BasketOptimizer.optimize(
            listOf("Latte"), catalog,
            transport = TransportProfile(VehicleType.VAN, FuelType.DIESEL, 9.0, 1.8),
        )

        assertTrue(bicycle.any { it.estimatedTravelCost == 0.0 && it.estimatedEmissionKgCo2 == 0.0 })
        assertTrue(dieselVan.any { it.estimatedTravelCost > 0.0 && it.estimatedEmissionKgCo2 > 0.0 })
    }
}
