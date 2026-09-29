package it.lagioiaproductions.shopeasily.data.repository.sources

import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import it.lagioiaproductions.shopeasily.data.repository.net.NonShopSites
import java.util.Locale
import kotlin.math.cos
import org.json.JSONArray
import org.json.JSONObject

/** A shop found on Google Maps through the official Places API (New). */
data class GooglePlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val website: String?,
    val types: List<String>,
    val address: String?,
    /** Google's main category for the place (e.g. "supermarket", "restaurant"). */
    val primaryType: String? = null,
)

/**
 * Official Google Places API (New). Reading Google Maps pages directly is not allowed
 * by Google's terms, so this source works only with the developer's own API key
 * (`GOOGLE_PLACES_API_KEY` in local.properties). Places has no product prices, but it
 * knows many small shops and their websites that are missing in OpenStreetMap: the app
 * then reads prices from those websites.
 */
class GooglePlacesSource(
    private val apiKey: String,
    private val http: HttpFetcher,
    /** Sent so the key can be restricted to this Android app in Google Cloud. */
    private val androidPackage: String? = null,
    private val androidCertSha1: String? = null,
) {
    val isEnabled: Boolean get() = apiKey.isNotBlank()

    /**
     * The official website Google knows for one specific shop: text search with the
     * shop's name around its exact position. Only a place within ~250 m whose name
     * shares a distinctive word is accepted, so namesakes elsewhere are never used.
     */
    fun findWebsite(name: String, latitude: Double, longitude: Double): String? {
        if (!isEnabled) return null
        val tokens = it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator.meaningfulTokens(name).toSet()
        if (tokens.isEmpty()) return null
        return text(name, latitude, longitude, 300.0)
            .filter { place -> distanceMeters(latitude, longitude, place.latitude, place.longitude) <= 250.0 }
            .filterNot { NonShopSites.isPlatformName(it.name) }
            .firstOrNull { place ->
                it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator.meaningfulTokens(place.name).any(tokens::contains)
            }
            ?.website
    }

    fun nearbyShops(latitude: Double, longitude: Double, radiusMeters: Int): List<GooglePlace> {
        if (!isEnabled) return emptyList()
        // Nearby Search returns at most 20 places: cover the area with a small grid of circles.
        val cell = (radiusMeters / 2).coerceIn(700, 5_000)
        val centers = gridCenters(latitude, longitude, radiusMeters, cell)
        val byType = centers.flatMap { (lat, lon) -> nearby(lat, lon, cell.toDouble(), SHOP_TYPES) }
        // Text searches target sustainable and local realities explicitly.
        val eco = ECO_QUERIES.flatMap { query -> text(query, latitude, longitude, radiusMeters.toDouble()) }
        // Text searches can return offices, pages or services: keep only food shops and markets.
        return (byType + eco).distinctBy(GooglePlace::id).filter { isFoodShop(it) }
    }

    private fun nearby(latitude: Double, longitude: Double, radius: Double, types: List<String>): List<GooglePlace> {
        val body = JSONObject()
            .put("includedTypes", JSONArray(types))
            .put("maxResultCount", 20)
            .put("languageCode", "it")
            .put("locationRestriction", circle(latitude, longitude, radius))
        return call("$BASE:searchNearby", body)
    }

    private fun text(query: String, latitude: Double, longitude: Double, radius: Double): List<GooglePlace> {
        val body = JSONObject()
            .put("textQuery", query)
            .put("pageSize", 20)
            .put("languageCode", "it")
            .put("locationBias", circle(latitude, longitude, radius.coerceAtMost(50_000.0)))
        return call("$BASE:searchText", body)
    }

    private fun call(url: String, body: JSONObject): List<GooglePlace> {
        val headers = buildMap {
            put("X-Goog-Api-Key", apiKey)
            put("X-Goog-FieldMask", FIELD_MASK)
            androidPackage?.let { put("X-Android-Package", it) }
            androidCertSha1?.let { put("X-Android-Cert", it) }
        }
        val json = http.postJson(url, body.toString(), headers) ?: return emptyList()
        return parse(json)
    }

    private fun circle(latitude: Double, longitude: Double, radius: Double) = JSONObject().put(
        "circle",
        JSONObject()
            .put("center", JSONObject().put("latitude", latitude).put("longitude", longitude))
            .put("radius", radius.coerceIn(1.0, 50_000.0)),
    )

    companion object {
        private const val BASE = "https://places.googleapis.com/v1/places"
        private const val FIELD_MASK =
            "places.id,places.displayName,places.location,places.websiteUri,places.types,places.primaryType," +
                "places.formattedAddress,places.businessStatus"
        /**
         * Places where food is sold to take home. The generic "food"/"store" types are not
         * enough: restaurants, bars and company offices (e.g. a place called "Meta") carry them.
         */
        val FOOD_TYPES = setOf(
            "supermarket", "grocery_store", "market", "bakery", "butcher_shop", "convenience_store",
            "food_store", "health_food_store", "farm", "discount_supermarket", "asian_grocery_store",
            "farmers_market", "discount_store", "hypermarket", "confectionery", "cheese_shop", "fish_store",
            "fruit_and_vegetable_store", "organic_food_store", "delicatessen", "wholesaler",
        )

        /** Places whose main activity is not selling groceries. */
        private val NOT_A_SHOP = setOf(
            "restaurant", "cafe", "bar", "meal_takeaway", "meal_delivery", "night_club", "coffee_shop",
            "corporate_office", "point_of_interest", "establishment", "lodging", "tourist_attraction",
            "school", "university", "church", "hospital", "gym", "store", "food",
        )

        /**
         * True for a real food shop or market: its primary type is a food shop, or (when
         * Google gives none) it has a food-shop type and no non-shop type. Platform names
         * ("Meta", "Facebook"…) are never a shop.
         */
        fun isFoodShop(place: GooglePlace): Boolean {
            if (NonShopSites.isPlatformName(place.name)) return false
            val primary = place.primaryType
            if (primary != null) return primary in FOOD_TYPES
            val hasFood = place.types.any(FOOD_TYPES::contains)
            val hasOther = place.types.any { it in NOT_A_SHOP && it !in setOf("point_of_interest", "establishment", "store", "food") }
            return hasFood && !hasOther
        }
        val SHOP_TYPES = listOf("supermarket", "grocery_store", "market", "bakery", "butcher_shop", "convenience_store")
        val ECO_QUERIES = listOf(
            "negozio biologico", "prodotti sfusi", "bottega commercio equo e solidale",
            "prodotti a km 0", "mercato contadino", "azienda agricola vendita diretta",
            "mercato Campagna Amica", "mercato rionale",
        )

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) *
                Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * 6_371_000.0 * Math.asin(Math.sqrt(a))
        }

        fun gridCenters(latitude: Double, longitude: Double, radius: Int, cell: Int): List<Pair<Double, Double>> {
            val steps = (radius / cell).coerceIn(0, 3)
            val dLat = cell / 111_000.0
            val dLon = cell / (111_000.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))
            return (-steps..steps).flatMap { i ->
                (-steps..steps).mapNotNull { j ->
                    if (i * i + j * j > steps * steps + 1) null else (latitude + i * dLat) to (longitude + j * dLon)
                }
            }
        }

        fun parse(json: String): List<GooglePlace> {
            val places = runCatching { JSONObject(json).optJSONArray("places") }.getOrNull() ?: return emptyList()
            return (0 until places.length()).mapNotNull { index ->
                val place = places.optJSONObject(index) ?: return@mapNotNull null
                val location = place.optJSONObject("location") ?: return@mapNotNull null
                val name = place.optJSONObject("displayName")?.optString("text").orEmpty().trim()
                if (name.isBlank()) return@mapNotNull null
                // Closed places are not somewhere to shop.
                if (place.optString("businessStatus").let { it.isNotBlank() && it != "OPERATIONAL" }) return@mapNotNull null
                val types = place.optJSONArray("types")?.let { array -> (0 until array.length()).map(array::optString) }.orEmpty()
                GooglePlace(
                    id = place.optString("id"),
                    name = name,
                    latitude = location.optDouble("latitude"),
                    longitude = location.optDouble("longitude"),
                    website = place.optString("websiteUri").takeIf(String::isNotBlank),
                    types = types,
                    address = place.optString("formattedAddress").takeIf(String::isNotBlank),
                    primaryType = place.optString("primaryType").takeIf(String::isNotBlank),
                )
            }
        }

        /** Maps Google place types to the OSM-like shop category used by the app. */
        fun category(types: List<String>): String = when {
            "supermarket" in types -> "supermarket"
            "butcher_shop" in types -> "butcher"
            "bakery" in types -> "bakery"
            "market" in types -> "marketplace"
            "convenience_store" in types -> "convenience"
            else -> "food"
        }

        /** Hints for the leaf score, derived from the name (Google has no organic/fair-trade tags). */
        fun sustainabilityTags(place: GooglePlace): Map<String, String> {
            val name = place.name.lowercase(Locale.ITALIAN)
            return buildMap {
                if ("market" in place.types && ("contadin" in name || "agricol" in name)) put("produce", "local")
                if ("farm" in place.types) put("shop", "farm")
            }
        }
    }
}
