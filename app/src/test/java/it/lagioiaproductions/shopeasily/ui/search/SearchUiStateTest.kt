package it.lagioiaproductions.shopeasily.ui.search

import it.lagioiaproductions.shopeasily.domain.EcoBasketEstimate
import it.lagioiaproductions.shopeasily.domain.EcoItemEstimate
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchUiStateTest {
    private val marketPlan = EcoBasketEstimate(
        storeId = "market",
        storeName = "Mercato locale",
        greenScore = 82,
        items = listOf(EcoItemEstimate("latte", 2.40, 3)),
        productsTotal = 2.40,
        travelCost = 0.60,
        emissionKg = 0.18,
        qualityScore = 88,
        qualityFromReviews = true,
        fairTrade = true,
    )

    @Test
    fun selectedMarketReplacesAllGlobalHomeMetrics() {
        val state = SearchUiState(
            manualCartProductsTotal = 10.0,
            manualCartFuelCost = 2.0,
            manualCartTotal = 12.0,
            manualCartEmissionKg = 1.0,
            selectedLocalStoreId = "market",
            ecoEstimatedPlans = mapOf("market" to marketPlan),
        )

        assertEquals(2.40, state.activeProductsTotal, 0.001)
        assertEquals(0.60, state.activeTravelCost, 0.001)
        assertEquals(3.00, state.activeCartTotal, 0.001)
        assertEquals(0.18, state.activeEmissionKg, 0.001)
    }

    @Test
    fun noSelectedMarketKeepsManualCartMetrics() {
        val state = SearchUiState(
            manualCartProductsTotal = 10.0,
            manualCartFuelCost = 2.0,
            manualCartTotal = 12.0,
            manualCartEmissionKg = 1.0,
            ecoEstimatedPlans = mapOf("market" to marketPlan),
        )

        assertEquals(10.0, state.activeProductsTotal, 0.001)
        assertEquals(2.0, state.activeTravelCost, 0.001)
        assertEquals(12.0, state.activeCartTotal, 0.001)
        assertEquals(1.0, state.activeEmissionKg, 0.001)
    }
}
