package it.lagioiaproductions.shopeasily.data.repository

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class RoutePoint(val latitude: Double, val longitude: Double)
data class RouteEstimate(val distanceMeters: Int, val durationSeconds: Int)

class RoutingRepository {
    fun distancesFrom(origin: RoutePoint, destinations: List<RoutePoint>): List<Int?> {
        if (destinations.isEmpty()) return emptyList()
        val limited = destinations.take(MAX_DESTINATIONS)
        val coordinates = (listOf(origin) + limited).joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "$BASE/table/v1/driving/$coordinates?sources=0&annotations=distance"
        val response = getJson(url) ?: return List(destinations.size) { null }
        val row = response.optJSONArray("distances")?.optJSONArray(0)
        val resolved = limited.indices.map { index ->
            row?.takeIf { !it.isNull(index + 1) }?.optDouble(index + 1)?.toInt()
        }
        return resolved + List(destinations.size - limited.size) { null }
    }

    fun route(points: List<RoutePoint>, roundTrip: Boolean = true): RouteEstimate? {
        if (points.size < 2) return null
        val routePoints = if (roundTrip && points.first() != points.last()) points + points.first() else points
        val coordinates = routePoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val response = getJson("$BASE/route/v1/driving/$coordinates?overview=false") ?: return null
        val route = response.optJSONArray("routes")?.optJSONObject(0) ?: return null
        return RouteEstimate(route.optDouble("distance").toInt(), route.optDouble("duration").toInt())
    }

    private fun getJson(value: String): JSONObject? = runCatching {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "ShopEasily/1.0")
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val BASE = "https://router.project-osrm.org"
        const val MAX_DESTINATIONS = 49
    }
}
