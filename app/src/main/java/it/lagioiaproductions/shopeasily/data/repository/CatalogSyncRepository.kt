package it.lagioiaproductions.shopeasily.data.repository

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CatalogSyncRepository(private val baseUrl: String) {
    suspend fun sync(latitude: Double, longitude: Double, radiusKm: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val radius = (radiusKm * 1_000).coerceIn(100, 20_000)
            val url = "$baseUrl/discover-and-ingest?lat=$latitude&lon=$longitude&radius_m=$radius"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
