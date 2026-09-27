package it.lagioiaproductions.shopeasily.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreSustainabilityTest {
    @Test
    fun organicOnlyShopGetsLeaf() {
        val result = StoreSustainability.evaluate("Bottega Verde", "convenience", mapOf("organic" to "only"))
        assertTrue(result.hasLeaf)
        assertTrue(result.score >= 50)
    }

    @Test
    fun limitedOrganicAndFairTradeOnlyWereIgnoredBefore() {
        assertTrue(StoreSustainability.evaluate("Altro", "convenience", mapOf("fair_trade" to "only")).hasLeaf)
        val limited = StoreSustainability.evaluate("Market", "supermarket", mapOf("organic" to "limited"))
        assertTrue(limited.score in 1..39)
    }

    @Test
    fun farmShopsMarketsAndBulkShopsAreRecognised() {
        assertTrue(StoreSustainability.evaluate("Cascina Rossi", "farm", mapOf("shop" to "farm")).hasLeaf)
        assertTrue(
            StoreSustainability.evaluate("Negozio Leggero", "convenience", mapOf("bulk_purchase" to "only")).hasLeaf,
        )
        assertTrue(StoreSustainability.evaluate("Mercato Trionfale", "marketplace", mapOf("amenity" to "marketplace")).score >= 25)
    }

    @Test
    fun organicChainIsRecognisedFromItsWikidataDescription() {
        val result = StoreSustainability.evaluate(
            "NaturaSì", "supermarket", mapOf("brand" to "NaturaSì"),
            brandDescription = "catena italiana di supermercati biologici",
        )
        assertTrue(result.hasLeaf)
    }

    @Test
    fun strongestTagIsInheritedAcrossBranches() {
        assertTrue(StoreSustainability.strongest(listOf(null, "yes", "only")) == "only")
        assertTrue(StoreSustainability.strongest(listOf(null, "no")) == null)
    }

    @Test
    fun ordinaryChainSupermarketHasNoLeaf() {
        val result = StoreSustainability.evaluate("Conad City", "supermarket", mapOf("brand" to "Conad"))
        assertFalse(result.hasLeaf)
        assertTrue(result.score == 0)
    }

    @Test
    fun independentGreengrocerGetsSmallBonusOnly() {
        val result = StoreSustainability.evaluate("Frutta da Mario", "greengrocer", mapOf("shop" to "greengrocer"))
        assertTrue(result.score in 1..39)
    }
}
