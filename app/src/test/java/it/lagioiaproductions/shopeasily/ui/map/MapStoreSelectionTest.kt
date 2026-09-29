package it.lagioiaproductions.shopeasily.ui.map

import it.lagioiaproductions.shopeasily.data.repository.NearbyStore
import org.junit.Assert.assertEquals
import org.junit.Test

class MapStoreSelectionTest {
    @Test
    fun overlappingMarkersSelectTheStoreNearestToTheTap() {
        val doc = store("doc", "doc*Torrino", 41.8157705, 12.4324988)
        val conad = store("conad", "Conad", 41.8154765, 12.4363502)

        val selected = closestStoreToTap(
            stores = listOf(doc, conad),
            renderedIds = setOf("doc", "conad"),
            latitude = 41.81548,
            longitude = 12.43634,
        )

        assertEquals("Conad", selected?.name)
    }

    private fun store(id: String, name: String, latitude: Double, longitude: Double) = NearbyStore(
        id = id,
        name = name,
        category = "supermarket",
        website = null,
        latitude = latitude,
        longitude = longitude,
        distanceMeters = 0,
        sustainable = false,
    )
}
