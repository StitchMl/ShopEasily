package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray

class RemoteOffersRepository(
    private val baseUrl: String,
    private val fallback: OffersRepository,
) : OffersRepository {
    override fun search(query: String): Flow<List<Offer>> = flow {
        val remote = runCatching { fetch(query) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            emit(remote)
        } else {
            fallback.search(query).collect(::emit)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetch(query: String): List<Offer> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = URL("$baseUrl/offers?q=$encoded&limit=500").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val name = item.getString("product_name")
                Offer(
                    id = stableId(item.getString("source_id"), name, item.getDouble("price")),
                    productName = name,
                    brand = null,
                    storeName = item.getString("store"),
                    price = item.getDouble("price"),
                    unitPrice = null,
                    distanceMeters = item.optInt("distance_meters", 0),
                    qualityScore = null,
                    sustainabilityLabels = emptyList(),
                    validUntil = item.optString("valid_until").takeIf { it.isNotBlank() && it != "null" },
                    loyaltyRequired = item.optBoolean("loyalty_required", false),
                    imageKey = imageFor(name),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun stableId(source: String, name: String, price: Double): Long =
        "$source|$name|$price".hashCode().toLong().and(0xffffffffL)

    private fun imageFor(name: String): ProductImageKey = when {
        name.contains("latte", true) || name.contains("yogurt", true) -> ProductImageKey.MILK
        name.contains("pasta", true) || name.contains("riso", true) -> ProductImageKey.PASTA
        name.contains("carne", true) || name.contains("pollo", true) -> ProductImageKey.MEAT
        name.contains("pane", true) || name.contains("biscott", true) -> ProductImageKey.BAKERY
        name.contains("deters", true) || name.contains("carta", true) -> ProductImageKey.HOUSEHOLD
        name.contains("frutta", true) || name.contains("verdura", true) -> ProductImageKey.PRODUCE
        else -> ProductImageKey.OTHER
    }
}
