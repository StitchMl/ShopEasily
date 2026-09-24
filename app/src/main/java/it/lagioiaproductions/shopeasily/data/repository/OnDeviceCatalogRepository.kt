package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NearbyStore(
    val name: String,
    val category: String,
    val website: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val sustainable: Boolean,
)

class OnDeviceCatalogRepository(
    context: Context,
    private val fallback: OffersRepository = FakeOffersRepository(),
) : OffersRepository {
    private val catalogFile = context.filesDir.resolve("scraped_catalog.json")

    override fun search(query: String): Flow<List<Offer>> = flow {
        val local = readCache().filter { offer ->
            query.isBlank() || offer.productName.contains(query, true) || offer.storeName.contains(query, true)
        }
        val demo = fallback.search(query).first()
        emit((local + demo).distinctBy { Triple(it.storeName, it.productName, it.price) })
    }.flowOn(Dispatchers.IO)

    suspend fun synchronize(latitude: Double, longitude: Double, radiusKm: Int): Int = withContext(Dispatchers.IO) {
        val shops = nearbyStores(latitude, longitude, radiusKm).filter { !it.website.isNullOrBlank() }
        val imported = shops.take(MAX_STORES).flatMap { shop ->
            runCatching { scanShop(shop, latitude, longitude) }.getOrDefault(emptyList())
        }
        val merged = (readCache() + imported)
            .distinctBy {
                Triple(
                    it.storeName.lowercase(Locale.ROOT),
                    it.productName.lowercase(Locale.ROOT),
                    it.price,
                )
            }
            .takeLast(MAX_CACHED_OFFERS)
        writeCache(merged)
        imported.size
    }

    suspend fun nearbyStores(latitude: Double, longitude: Double, radiusKm: Int): List<NearbyStore> =
        withContext(Dispatchers.IO) {
            discoverShops(latitude, longitude, (radiusKm * 1_000).coerceIn(500, 20_000))
        }

    private fun discoverShops(latitude: Double, longitude: Double, radius: Int): List<NearbyStore> {
        val query = """[out:json][timeout:25];(
          nwr(around:$radius,$latitude,$longitude)[shop~"^(supermarket|convenience|discount|deli|butcher|greengrocer|bakery|farm)$"];
          nwr(around:$radius,$latitude,$longitude)[amenity="marketplace"];
        );out center tags;""".trimIndent()
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val response = request(OVERPASS_URL, "POST", body.toByteArray()) ?: return emptyList()
        val elements = JSONObject(response.toString(Charsets.UTF_8)).optJSONArray("elements") ?: return emptyList()
        return buildList {
            for (index in 0 until elements.length()) {
                val item = elements.getJSONObject(index)
                val tags = item.optJSONObject("tags") ?: continue
                val name = tags.optString("name")
                val center = item.optJSONObject("center") ?: item
                if (name.isBlank() || !center.has("lat") || !center.has("lon")) continue
                val shopLatitude = center.getDouble("lat")
                val shopLongitude = center.getDouble("lon")
                add(
                    NearbyStore(
                        name = name,
                        category = tags.optString("shop").ifBlank { "marketplace" },
                        website = tags.optString("website").ifBlank {
                            tags.optString("contact:website")
                        }.takeIf(String::isNotBlank),
                        latitude = shopLatitude,
                        longitude = shopLongitude,
                        distanceMeters = distanceMeters(latitude, longitude, shopLatitude, shopLongitude),
                        sustainable = tags.optString("organic") in setOf("yes", "only") ||
                            tags.optString("fair_trade") == "yes" || tags.optString("produce") == "local",
                    ),
                )
            }
        }.distinctBy { Triple(it.name, it.latitude, it.longitude) }.sortedBy(NearbyStore::distanceMeters)
    }

    private fun scanShop(shop: NearbyStore, userLat: Double, userLon: Double): List<Offer> {
        val website = shop.website ?: return emptyList()
        if (!isPublicUrl(website) || !robotsAllows(website)) return emptyList()
        val homepage = request(website)?.toString(Charsets.UTF_8) ?: return emptyList()
        val links = candidateLinks(website, homepage).take(MAX_DOCUMENTS_PER_STORE)
        val distance = distanceMeters(userLat, userLon, shop.latitude, shop.longitude)
        val result = parseStructuredProducts(homepage, shop, distance).toMutableList()
        links.forEachIndexed { index, link ->
            if (!isPublicUrl(link) || !robotsAllows(link)) return@forEachIndexed
            val bytes = request(link) ?: return@forEachIndexed
            if (bytes.size > MAX_DOCUMENT_BYTES) return@forEachIndexed
            if (link.substringBefore('?').endsWith(".pdf", true) || bytes.startsWithPdfHeader()) {
                result += parsePdf(bytes, shop, distance, index)
            } else {
                result += parseStructuredProducts(bytes.toString(Charsets.UTF_8), shop, distance)
            }
        }
        return result
    }

    private fun candidateLinks(base: String, html: String): List<String> {
        val baseUri = URI(base)
        return HREF.findAll(html).mapNotNull { match ->
            runCatching { baseUri.resolve(match.groupValues[1]).toString() }.getOrNull()
        }.filter { link ->
            runCatching { URI(link).host == baseUri.host }.getOrDefault(false) &&
                (link.endsWith(".pdf", true) || DISCOVERY_WORDS.any { link.contains(it, true) })
        }.distinct().toList()
    }

    private fun parseStructuredProducts(html: String, shop: NearbyStore, distance: Int): List<Offer> {
        val result = mutableListOf<Offer>()
        JSON_LD.findAll(html).forEach { match ->
            val payload = runCatching { JSONObject(match.groupValues[1]) }.getOrNull() ?: return@forEach
            walkJson(payload).forEach { product ->
                val name = product.optString("name")
                val offers = product.opt("offers")
                val offer = if (offers is JSONObject) offers else (offers as? JSONArray)?.optJSONObject(0)
                val price = offer?.opt("price")?.toString()?.replace(',', '.')?.toDoubleOrNull()
                val image = product.opt("image")
                val imageUrl = (
                    if (image is String) image
                    else if (image is JSONArray) image.optString(0)
                    else (image as? JSONObject)?.optString("url")
                    )?.takeIf { it.isNotBlank() && isPublicUrl(it) }
                if (name.isNotBlank() && price != null) {
                    result += offer(name, shop.name, price, distance, productImageUrl = imageUrl, storeWebsite = shop.website)
                }
            }
        }
        return result
    }

    private fun walkJson(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> {
                val type = value.opt("@type")
                if (type == "Product" || type is JSONArray && (0 until type.length()).any { type.optString(it) == "Product" }) {
                    yield(value)
                }
                value.keys().forEach { yieldAll(walkJson(value.opt(it))) }
            }
            is JSONArray -> for (index in 0 until value.length()) yieldAll(walkJson(value.opt(index)))
        }
    }

    private fun parsePdf(bytes: ByteArray, shop: NearbyStore, distance: Int, documentIndex: Int): List<Offer> =
        runCatching {
            PDDocument.load(bytes).use { document ->
                val lines = PDFTextStripper().getText(document).lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                lines.mapIndexedNotNull { index, line ->
                    val match = PRICE.find(line) ?: return@mapIndexedNotNull null
                    val price = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapIndexedNotNull null
                    val name = line.replace(match.value, "").trim(' ', '-', '–', ':').ifBlank { lines.getOrNull(index - 1).orEmpty() }
                    name.takeIf { it.length in 2..160 }?.let {
                        offer(it, shop.name, price, distance, documentIndex, storeWebsite = shop.website)
                    }
                }
            }
        }.getOrDefault(emptyList())

    private fun offer(
        name: String,
        store: String,
        price: Double,
        distance: Int,
        salt: Int = 0,
        productImageUrl: String? = null,
        storeWebsite: String? = null,
    ) = Offer(
        id = "$store|$name|$price|$salt".hashCode().toLong().and(0xffffffffL),
        productName = name, brand = null, storeName = store, price = price, unitPrice = null,
        distanceMeters = distance, qualityScore = null, sustainabilityLabels = emptyList(),
        validUntil = null, imageKey = imageFor(name), productImageUrl = productImageUrl, storeWebsite = storeWebsite,
    )

    private fun request(url: String, method: String = "GET", body: ByteArray? = null): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { input -> input.readLimited(MAX_DOCUMENT_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun robotsAllows(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val robots = request("${uri.scheme}://${uri.authority}/robots.txt")?.toString(Charsets.UTF_8) ?: return false
        val path = uri.rawPath.ifBlank { "/" }
        return robots.lineSequence().filter { it.trim().startsWith("Disallow:", true) }
            .map { it.substringAfter(':').trim() }.none { it == "/" || it.isNotBlank() && path.startsWith(it) }
    }

    private fun isPublicUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && uri.host != null &&
            InetAddress.getAllByName(uri.host).all { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress && !address.isSiteLocalAddress
            }
    }.getOrDefault(false)

    private fun readCache(): List<Offer> = runCatching {
        val array = JSONArray(catalogFile.readText())
        List(array.length()) { index -> array.getJSONObject(index).toOffer() }
    }.getOrDefault(emptyList())

    private fun writeCache(offers: List<Offer>) {
        val array = JSONArray()
        offers.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("name", item.productName); put("store", item.storeName)
                put("price", item.price); put("distance", item.distanceMeters); put("image", item.imageKey.name)
                put("productImageUrl", item.productImageUrl); put("storeWebsite", item.storeWebsite)
            })
        }
        catalogFile.writeText(array.toString())
    }

    private fun JSONObject.toOffer() = Offer(
        id = getLong("id"), productName = getString("name"), brand = null,
        storeName = getString("store"), price = getDouble("price"), unitPrice = null,
        distanceMeters = getInt("distance"), qualityScore = null,
        sustainabilityLabels = emptyList(), validUntil = null,
        imageKey = runCatching { ProductImageKey.valueOf(getString("image")) }.getOrDefault(ProductImageKey.OTHER),
        productImageUrl = optString("productImageUrl").takeIf(String::isNotBlank),
        storeWebsite = optString("storeWebsite").takeIf(String::isNotBlank),
    )

    private fun imageFor(name: String) = when {
        name.contains("yogurt", true) -> ProductImageKey.YOGURT
        name.contains("formagg", true) || name.contains("mozzarell", true) || name.contains("parmig", true) -> ProductImageKey.CHEESE
        name.contains("latte", true) -> ProductImageKey.MILK
        name.contains("riso", true) -> ProductImageKey.RICE
        name.contains("pasta", true) -> ProductImageKey.PASTA
        listOf("legum", "fagiol", "ceci", "lenticch", "piselli").any { name.contains(it, true) } -> ProductImageKey.LEGUMES
        name.contains("uov", true) -> ProductImageKey.EGGS
        name.contains("carne", true) || name.contains("pollo", true) -> ProductImageKey.MEAT
        listOf("pesce", "salmone", "tonno", "merluzzo", "orata", "branzino").any { name.contains(it, true) } -> ProductImageKey.FISH
        name.contains("caff", true) -> ProductImageKey.COFFEE
        name.contains("acqua", true) -> ProductImageKey.WATER
        name.contains("olio", true) -> ProductImageKey.OIL
        name.contains("pane", true) || name.contains("biscott", true) -> ProductImageKey.BAKERY
        name.contains("deters", true) || name.contains("carta", true) -> ProductImageKey.HOUSEHOLD
        listOf(
            "frutta", "mela", "mele", "pera", "pere", "banana", "arancia", "limone", "mandarino",
            "fragola", "ciliegia", "pesca", "albicocca", "kiwi", "uva", "melone", "anguria", "ananas",
        ).any { name.contains(it, true) } -> ProductImageKey.FRUIT
        listOf(
            "verdura", "pomodor", "carota", "zucchin", "melanzan", "peperon", "patat", "cipoll",
            "insalat", "lattuga", "broccol", "cavol", "spinac", "finocch", "sedano", "zucca",
        ).any { name.contains(it, true) } -> ProductImageKey.VEGETABLE
        else -> ProductImageKey.OTHER
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return (6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))).roundToInt()
    }

    private fun ByteArray.startsWithPdfHeader() = size >= 4 && copyOfRange(0, 4).toString(Charsets.US_ASCII) == "%PDF"

    private companion object {
        const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        const val USER_AGENT = "ShopEasily/0.2 (+https://github.com/StitchMl/ShopEasily)"
        const val MAX_STORES = 30
        const val MAX_DOCUMENTS_PER_STORE = 6
        const val MAX_DOCUMENT_BYTES = 15 * 1024 * 1024
        const val MAX_CACHED_OFFERS = 10_000
        val DISCOVERY_WORDS = listOf("offert", "volantin", "catalog", "promozion", "promo")
        val HREF = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
        val JSON_LD = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val PRICE = Regex("""(?:€\s*)?(\d{1,4}[,.]\d{2})(?:\s*€)?""")
    }
}
