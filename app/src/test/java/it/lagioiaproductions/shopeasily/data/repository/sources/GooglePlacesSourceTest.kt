package it.lagioiaproductions.shopeasily.data.repository.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun onlyFoodShopsAndMarketsAreKept() {
        fun place(name: String, types: List<String>, primary: String?) =
            GooglePlace("id", name, 41.9, 12.5, null, types, null, primary)
        assertFalse(GooglePlacesSource.isFoodShop(place("Meta", listOf("corporate_office", "food", "store"), null)))
        assertFalse(GooglePlacesSource.isFoodShop(place("Meta", listOf("grocery_store"), "grocery_store")))
        assertFalse(GooglePlacesSource.isFoodShop(place("Trattoria Da Mario", listOf("restaurant", "food"), "restaurant")))
        assertFalse(GooglePlacesSource.isFoodShop(place("Bar Sport", listOf("bar", "grocery_store"), null)))
        assertTrue(GooglePlacesSource.isFoodShop(place("Mercato Laurentino", listOf("market", "point_of_interest"), "market")))
        assertTrue(GooglePlacesSource.isFoodShop(place("Bottega Bio", listOf("grocery_store", "food", "store"), null)))
    }

    @Test
    fun closedPlacesAreSkippedAndPrimaryTypeIsRead() {
        val json = """{"places":[
            {"id":"a","displayName":{"text":"Forno Aperto"},"location":{"latitude":41.9,"longitude":12.5},
             "types":["bakery"],"primaryType":"bakery","businessStatus":"OPERATIONAL"},
            {"id":"b","displayName":{"text":"Forno Chiuso"},"location":{"latitude":41.9,"longitude":12.5},
             "types":["bakery"],"businessStatus":"CLOSED_PERMANENTLY"}]}"""
        val places = GooglePlacesSource.parse(json)
        assertEquals(listOf("Forno Aperto"), places.map(GooglePlace::name))
        assertEquals("bakery", places.single().primaryType)
    }
}
