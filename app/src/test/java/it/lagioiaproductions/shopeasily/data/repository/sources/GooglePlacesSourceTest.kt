package it.lagioiaproductions.shopeasily.data.repository.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlacesSourceTest {
    @Test
    fun parsesPlacesAnswer() {
        val json = """{"places":[{"id":"abc","displayName":{"text":"Bottega Bio Rossi","languageCode":"it"},
            "location":{"latitude":41.9,"longitude":12.5},"websiteUri":"http://bottegabiorossi.it/",
            "types":["grocery_store","food","store"],"formattedAddress":"Via Appia 21, Roma"}]}"""
        val place = GooglePlacesSource.parse(json).single()
        assertEquals("Bottega Bio Rossi", place.name)
        assertEquals("http://bottegabiorossi.it/", place.website)
        assertEquals("food", GooglePlacesSource.category(place.types))
    }

    @Test
    fun gridCoversTheRadius() {
        val centers = GooglePlacesSource.gridCenters(41.9, 12.5, 10_000, 5_000)
        assertTrue(centers.size in 5..25)
        assertTrue(centers.contains(41.9 to 12.5))
    }
}
