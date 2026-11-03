package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

data class RoutePoint(val latitude: Double, val longitude: Double)
data class RouteEstimate(val distanceMeters: Int, val durationSeconds: Int)

/** Free OSRM routing with persistent cache and an offline road-distance estimate. */
class RoutingRepository(context: Context? = null) {
    private val cache = context?.applicationContext
        ?.getSharedPreferences("routing_cache_v2", Context.MODE_PRIVATE)

    fun distancesFrom(origin: RoutePoint, destinations: List<RoutePoint>): List<Int?> {
        if (destinations.isEmpty()) return emptyList()
        val result = destinations.map { cachedDistance(origin, it) }.toMutableList()
        destinations.indices.filter { result[it] == null }.chunked(MAX_DESTINATIONS).forEach { indexes ->
            val points = indexes.map(destinations::get)
            val remote = table(origin, points) ?: return@forEach
            indexes.forEachIndexed { localIndex, originalIndex ->
                remote.getOrNull(localIndex)?.let { distance ->
                    result[originalIndex] = distance
                    saveDistance(origin, destinations[originalIndex], distance)
                }
            }
        }
        return result.mapIndexed { index, value -> value ?: estimatedRoadMeters(origin, destinations[index]) }
    }

    fun route(points: List<RoutePoint>, roundTrip: Boolean = true): RouteEstimate? {
        if (points.size < 2) return null
        val routePoints = if (roundTrip && points.first() != points.last()) points + points.first() else points
        cachedRoute(routePoints)?.let { return it }
        val coordinates = routePoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        BASES.forEach { base ->
            val json = getJson("$base/route/v1/driving/$coordinates?overview=false") ?: return@forEach
            val item = json.optJSONArray("routes")?.optJSONObject(0) ?: return@forEach
            val estimate = RouteEstimate(item.optDouble("distance").roundToInt(), item.optDouble("duration").roundToInt())
            saveRoute(routePoints, estimate)
            return estimate
        }
        val meters = routePoints.zipWithNext().sumOf { (a, b) -> estimatedRoadMeters(a, b) }
        return RouteEstimate(meters, (meters / FALLBACK_METERS_PER_SECOND).roundToInt())
    }

    private fun table(origin: RoutePoint, destinations: List<RoutePoint>): List<Int?>? {
        val coordinates = (listOf(origin) + destinations).joinToString(";") { "${it.longitude},${it.latitude}" }
        BASES.forEach { base ->
            val row = getJson("$base/table/v1/driving/$coordinates?sources=0&annotations=distance")
                ?.optJSONArray("distances")?.optJSONArray(0) ?: return@forEach
            return destinations.indices.map { index ->
                row.takeUnless { it.isNull(index + 1) }?.optDouble(index + 1)?.roundToInt()
            }
        }
        return null
    }

    private fun cachedDistance(a: RoutePoint, b: RoutePoint): Int? = cached(distanceKey(a, b))?.optInt("m")
    private fun saveDistance(a: RoutePoint, b: RoutePoint, meters: Int) = save(distanceKey(a, b), JSONObject().put("m", meters))

    private fun cachedRoute(points: List<RoutePoint>): RouteEstimate? = cached(routeKey(points))?.let {
        RouteEstimate(it.optInt("m"), it.optInt("s"))
    }

    private fun saveRoute(points: List<RoutePoint>, route: RouteEstimate) = save(
        routeKey(points), JSONObject().put("m", route.distanceMeters).put("s", route.durationSeconds),
    )

    private fun cached(key: String): JSONObject? {
        val raw = cache?.getString(key, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return json.takeIf { System.currentTimeMillis() - it.optLong("at") <= CACHE_TTL_MS }
    }

    private fun save(key: String, value: JSONObject) {
        cache?.edit()?.putString(key, value.put("at", System.currentTimeMillis()).toString())?.apply()
    }

    private fun distanceKey(a: RoutePoint, b: RoutePoint) = "d:${pointKey(a)}:${pointKey(b)}"
    private fun routeKey(points: List<RoutePoint>) = "r:" + points.joinToString(":", transform = ::pointKey)
    private fun pointKey(point: RoutePoint) = "%.4f,%.4f".format(java.util.Locale.US, point.latitude, point.longitude)

    private fun getJson(value: String): JSONObject? = runCatching {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val text = connection.inputStream.bufferedReader().use { reader ->
                val output = CharArray(MAX_RESPONSE_CHARS)
                val length = reader.read(output)
                if (length < 0) "" else String(output, 0, length)
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        private val BASES = listOf(
            "https://router.project-osrm.org",
            "https://routing.openstreetmap.de/routed-car",
        )
        private const val USER_AGENT = "ShopEasily/1.0 (it.lagioiaproductions.shopeasily)"
        private const val MAX_DESTINATIONS = 40
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1_000
        private const val FALLBACK_METERS_PER_SECOND = 9.72 // 35 km/h in urban traffic.

        internal fun estimatedRoadMeters(a: RoutePoint, b: RoutePoint): Int {
            val earthRadius = 6_371_000.0
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return (earthRadius * 2 * asin(sqrt(h)) * 1.25).roundToInt()
        }
    }
}
