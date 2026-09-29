package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.repository.sources.GooglePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreIdentityMatcherTest {
    @Test
    fun exactPositionCanUpdateACompletelyChangedBannerWithoutMergingNearbyBranch() {
        val stores = listOf(
            store("legacy", "doc*Torrino", 41.8157705, 12.4324988),
            store("other", "Conad", 41.8154765, 12.4363502),
        )
        val current = place("Conad Superstore Torrino", 41.8157750, 12.4325000)

        val match = StoreIdentityMatcher.match(stores, current)

        assertEquals(0, match?.index)
        assertTrue(match?.adoptSourceName == true)
    }

    @Test
    fun ambiguousCoLocatedMarketStallsAreNotMergedByPositionAlone() {
        val stores = listOf(
            store("a", "Banco Rossi", 41.9, 12.5),
            store("b", "Banco Verdi", 41.90001, 12.50001),
        )
        assertNull(StoreIdentityMatcher.match(stores, place("Nuova Bottega", 41.9, 12.5)))
    }

    private fun store(id: String, name: String, latitude: Double, longitude: Double) = NearbyStore(
        id = id, name = name, category = "supermarket", website = null,
        latitude = latitude, longitude = longitude, distanceMeters = 0, sustainable = false,
    )

    private fun place(name: String, latitude: Double, longitude: Double) = GooglePlace(
        id = "google", name = name, latitude = latitude, longitude = longitude,
        website = null, types = listOf("supermarket"), address = null,
    )
}
