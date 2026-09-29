package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoBasketEstimatorTest {
    private val catalog = listOf(1.0, 2.0, 100.0).mapIndexed { index, price ->
        CatalogPrice(
            store = Store(index.toLong(), "Store $index", StoreChannel.PHYSICAL, null, null, 500),
            productName = "Latte intero 1 L",
            aliases = setOf("latte"),
            price = price,
            promotional = false,
        )
    }

    @Test
    fun excludesStoreBelowThresholdAndUsesRobustMedian() {
        val plans = EcoBasketEstimator.estimate(
            requestedItems = listOf("latte"),
            catalog = catalog,
            stores = listOf(
                EcoStoreCandidate("low", "Catena", 100, 39),
                EcoStoreCandidate("green", "Mercato", 1_000, 70),
            ),
            transport = TransportProfile(),
        )

        assertEquals(listOf("green"), plans.map(EcoBasketEstimate::storeId))
        assertEquals(2.16, plans.single().productsTotal, 0.001)
        assertEquals(0, plans.single().lowConfidenceItems)
        assertTrue(plans.single().travelCost > 0.0)
        assertTrue(plans.single().emissionKg > 0.0)
    }

    @Test
    fun unknownProductUsesClearlyLowConfidenceFallback() {
        val plan = EcoBasketEstimator.estimate(
            requestedItems = listOf("prodotto sconosciuto"),
            catalog = emptyList(),
            stores = listOf(EcoStoreCandidate("market", "Mercato", 0, 50)),
            transport = TransportProfile(),
        ).single()

        assertEquals(3.50, plan.productsTotal, 0.001)
        assertEquals(1, plan.lowConfidenceItems)
    }
}
