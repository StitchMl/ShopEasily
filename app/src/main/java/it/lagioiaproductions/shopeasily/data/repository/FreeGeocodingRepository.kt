package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import android.location.Geocoder
import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Nominatim first, Android geocoder fallback. No key, account, billing or user tracking. */
class FreeGeocodingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cache = appContext.getSharedPreferences("free_geocoding_v1", Context.MODE_PRIVATE)
    private val http = HttpFetcher(USER_AGENT)

    suspend fun search(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return@withContext null
        cached(normalized)?.let { return@withContext it }

        val remote = requestMutex.withLock {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
            val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
            http.getText(
                "$NOMINATIM/search?format=jsonv2&limit=1&countrycodes=it&q=$encoded",
                maxBytes = 256 * 1024,
                accept = "application/json",
            )?.let(::parse).also { lastRequestAt = System.currentTimeMillis() }
        }
        val result = remote ?: androidFallback(normalized)
        if (result != null) save(normalized, result)
        result
    }

    private fun cached(query: String): Pair<Double, Double>? {
        val raw = cache.getString(query.hashCode().toString(), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optString("q") != query || System.currentTimeMillis() - json.optLong("at") > CACHE_TTL_MS) return null
        return json.optDouble("lat").takeIf(Double::isFinite)?.let { lat ->
            json.optDouble("lon").takeIf(Double::isFinite)?.let { lon -> lat to lon }
        }
    }

    private fun save(query: String, point: Pair<Double, Double>) {
        val json = JSONObject().put("q", query).put("lat", point.first).put("lon", point.second)
            .put("at", System.currentTimeMillis())
        cache.edit().putString(query.hashCode().toString(), json.toString()).apply()
    }

    @Suppress("DEPRECATION")
    private fun androidFallback(query: String): Pair<Double, Double>? = runCatching {
        Geocoder(appContext, Locale.ITALY).getFromLocationName(query, 1)?.firstOrNull()
            ?.let { it.latitude to it.longitude }
    }.getOrNull()

    companion object {
        private const val NOMINATIM = "https://nominatim.openstreetmap.org"
        private const val USER_AGENT = "ShopEasily/1.0 (it.lagioiaproductions.shopeasily; https://github.com/StitchMl/ShopEasily)"
        private const val MIN_INTERVAL_MS = 1_100L
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        private val requestMutex = Mutex()
        @Volatile private var lastRequestAt = 0L

        internal fun parse(json: String): Pair<Double, Double>? {
            val item = runCatching { JSONArray(json).optJSONObject(0) }.getOrNull() ?: return null
            val lat = item.optString("lat").toDoubleOrNull() ?: return null
            val lon = item.optString("lon").toDoubleOrNull() ?: return null
            return lat.takeIf { it in -90.0..90.0 }?.let { validLat ->
                lon.takeIf { it in -180.0..180.0 }?.let { validLon -> validLat to validLon }
            }
        }
    }
}
