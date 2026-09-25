package it.lagioiaproductions.shopeasily.data.repository

import java.security.MessageDigest
import java.util.Locale

object StoreDeduplicator {
    private val aliases = mapOf(
        "carrefour market" to "carrefour",
        "carrefour express" to "carrefour",
        "coop.fi" to "coop",
        "incoop" to "coop",
        "conad city" to "conad",
        "conad superstore" to "conad",
        "eurospin lazio" to "eurospin",
        "lidl italia" to "lidl",
        "m.a. supermercati" to "ma supermercati",
    )

    fun canonicalName(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9àèéìòù]+"), " ")
            .trim()
        return aliases.entries.firstOrNull { normalized.contains(it.key) }?.value ?: normalized
    }

    fun stableId(name: String, latitude: Double, longitude: Double): String {
        val areaLat = (latitude * 1_000).toInt()
        val areaLon = (longitude * 1_000).toInt()
        val raw = "${canonicalName(name)}|$areaLat|$areaLon"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
    }

    fun merge(stores: List<NearbyStore>): List<NearbyStore> = stores
        .groupBy { stableId(it.name, it.latitude, it.longitude) }
        .map { (id, matches) ->
            val best = matches.maxByOrNull { score(it) } ?: matches.first()
            best.copy(
                id = id,
                website = matches.firstNotNullOfOrNull(NearbyStore::website),
                sustainable = matches.any(NearbyStore::sustainable),
                distanceMeters = matches.minOf(NearbyStore::distanceMeters),
            )
        }
        .sortedBy(NearbyStore::distanceMeters)

    private fun score(store: NearbyStore): Int =
        (if (!store.website.isNullOrBlank()) 2 else 0) + (if (store.sustainable) 1 else 0)
}
