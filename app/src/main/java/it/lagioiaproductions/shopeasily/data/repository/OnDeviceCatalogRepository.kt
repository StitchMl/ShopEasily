@file:Suppress("SpellCheckingInspection")

package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.data.local.OfferEntity
import it.lagioiaproductions.shopeasily.data.local.ShopEasilyDatabase
import it.lagioiaproductions.shopeasily.data.local.SourceState
import it.lagioiaproductions.shopeasily.data.local.SourceStatusEntity
import it.lagioiaproductions.shopeasily.data.local.StoreEntity
import it.lagioiaproductions.shopeasily.data.repository.parsers.ChainSourceAdapters
import it.lagioiaproductions.shopeasily.data.repository.parsers.OcrFlyerReader
import it.lagioiaproductions.shopeasily.data.repository.parsers.OfferTextParser
import it.lagioiaproductions.shopeasily.data.repository.parsers.HtmlProductParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
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
    val id: String = "",
    val name: String,
    val category: String,
    val website: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val sustainable: Boolean,
)

class OnDeviceCatalogRepository(
    private val context: Context,
    private val fallback: OffersRepository = FakeOffersRepository(),
) : OffersRepository {
    private val catalogFile = context.filesDir.resolve("scraped_catalog.json")
    private val dao = ShopEasilyDatabase.get(context).catalogDao()
    private val routing = RoutingRepository()

    fun observeSourceStatuses(): Flow<List<SourceStatusEntity>> = dao.observeSourceStatuses()

    suspend fun storedStores(): List<NearbyStore> = dao.stores().map { store ->
        NearbyStore(
            id = store.id,
            name = store.name,
            category = store.category,
            website = store.website,
            latitude = store.latitude,
            longitude = store.longitude,
            distanceMeters = store.distanceMeters,
            sustainable = store.sustainable,
        )
    }

    suspend fun catalogPrices(): List<CatalogPrice> {
        val stores = dao.stores().associateBy(StoreEntity::id)
        return dao.activeOffers(System.currentTimeMillis()).mapNotNull { offer ->
            val store = stores[offer.storeId] ?: return@mapNotNull null
            CatalogPrice(
                store = Store(
                    id = store.id.hashCode().toLong().and(0xffffffffL),
                    name = store.name,
                    channel = StoreChannel.PHYSICAL,
                    latitude = store.latitude,
                    longitude = store.longitude,
                    distanceMeters = store.distanceMeters,
                ),
                productName = offer.productName,
                aliases = setOf(offer.productName.lowercase(Locale.ROOT)),
                price = offer.price,
                promotional = offer.promotional,
                ecological = store.sustainable,
            )
        }
    }

    override fun search(query: String): Flow<List<Offer>> = flow {
        migrateLegacyCacheIfNeeded()
        val sustainableStores = dao.stores().associate { it.id to it.sustainable }
        val local = dao.activeOffers(System.currentTimeMillis()).map { entity ->
            entity.toOffer(sustainableStores[entity.storeId] == true)
        }.filter { offer ->
            query.isBlank() || offer.productName.contains(query, true) || offer.storeName.contains(query, true)
        }
        val demo = if (local.isEmpty()) fallback.search(query).first() else emptyList()
        emit((local + demo).distinctBy { Triple(it.storeName, it.productName, it.price) })
    }.flowOn(Dispatchers.IO)

    suspend fun synchronize(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shops = nearbyStores(latitude, longitude, radiusKm)
        dao.upsertStores(shops.map { it.toEntity(now) })
        dao.deleteExpired(now)
        var importedCount = 0
        shops.forEachIndexed { index, shop ->
            val sources = (shop.website?.let(::listOf) ?: discoverPublicSources(shop.name))
                .distinct().take(MAX_SOURCE_PAGES_PER_STORE)
            if (sources.isEmpty()) {
                dao.upsertStatus(shop.status(SourceState.UNAVAILABLE, now, 0, "Sito non indicato"))
                onProgress(index + 1, shops.size)
                return@forEachIndexed
            }
            val resolvedShop = shop.copy(website = sources.first())
            dao.upsertStores(listOf(resolvedShop.toEntity(now)))
            dao.upsertStatus(resolvedShop.status(SourceState.PENDING, now, 0, null))
            val result = runCatching {
                val initial = sources.flatMap { source -> scanShop(resolvedShop.copy(website = source), latitude, longitude) }
                val fallbackSources = if (initial.isEmpty() && shop.website != null) {
                    discoverPublicSources(shop.name).filterNot(sources::contains).take(MAX_DISCOVERED_SOURCES)
                } else {
                    emptyList()
                }
                (initial + fallbackSources.flatMap { source ->
                    scanShop(resolvedShop.copy(website = source), latitude, longitude)
                })
                    .distinctBy { Triple(it.productName.lowercase(Locale.ROOT), it.price, it.storeName) }
            }
            result.onSuccess { offers ->
                val entities = offers.map { it.toEntity(resolvedShop, it.storeWebsite ?: sources.first(), now) }
                dao.replaceStoreOffers(shop.id, entities)
                importedCount += entities.size
                dao.upsertStatus(
                    resolvedShop.status(
                        if (entities.isEmpty()) SourceState.NO_OFFERS else SourceState.UPDATED,
                        now,
                        entities.size,
                        if (entities.isEmpty()) "Nessuna offerta leggibile" else null,
                    ),
                )
            }.onFailure { error ->
                dao.upsertStatus(resolvedShop.status(SourceState.UNAVAILABLE, now, 0, error.javaClass.simpleName))
            }
            onProgress(index + 1, shops.size)
        }
        importedCount
    }

    suspend fun nearbyStores(latitude: Double, longitude: Double, radiusKm: Int): List<NearbyStore> =
        withContext(Dispatchers.IO) {
            val stores = discoverShops(latitude, longitude, (radiusKm * 1_000).coerceIn(500, 20_000))
            val roadDistances = routing.distancesFrom(
                RoutePoint(latitude, longitude),
                stores.map { RoutePoint(it.latitude, it.longitude) },
            )
            stores.mapIndexed { index, store ->
                store.copy(distanceMeters = roadDistances.getOrNull(index) ?: store.distanceMeters)
            }.sortedBy(NearbyStore::distanceMeters)
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
                        website = StoreWebsiteResolver.resolve(name, tags.optString("website").ifBlank {
                            tags.optString("contact:website")
                        }.takeIf(String::isNotBlank)),
                        latitude = shopLatitude,
                        longitude = shopLongitude,
                        distanceMeters = distanceMeters(latitude, longitude, shopLatitude, shopLongitude),
                        sustainable = tags.optString("organic") in setOf("yes", "only") ||
                            tags.optString("fair_trade") == "yes" || tags.optString("produce") == "local",
                    ),
                )
            }
        }.let(StoreDeduplicator::merge)
    }

    private fun scanShop(shop: NearbyStore, userLat: Double, userLon: Double): List<Offer> {
        val website = shop.website ?: return emptyList()
        if (!isPublicUrl(website) || !robotsAllows(website)) return emptyList()
        val homepage = request(website)?.toString(Charsets.UTF_8) ?: return emptyList()
        val links = candidateLinks(shop.name, website, homepage).take(MAX_DOCUMENTS_PER_STORE)
        val distance = distanceMeters(userLat, userLon, shop.latitude, shop.longitude)
        val result = parseHtmlProducts(homepage, website, shop, distance, promotional = false).toMutableList()
        val productDetailLinks = HtmlProductParser.detailLinks(homepage, website).toMutableSet()
        links.forEachIndexed { index, link ->
            if (!isPublicUrl(link) || !robotsAllows(link)) return@forEachIndexed
            val bytes = request(link) ?: return@forEachIndexed
            if (bytes.size > MAX_DOCUMENT_BYTES) return@forEachIndexed
            result += if (link.substringBefore('?').endsWith(".pdf", true) || bytes.startsWithPdfHeader()) {
                parsePdf(bytes, shop, distance, index)
            } else {
                val html = bytes.toString(Charsets.UTF_8)
                productDetailLinks += HtmlProductParser.detailLinks(html, link)
                parseHtmlProducts(
                    html,
                    link,
                    shop,
                    distance,
                    promotional = PROMOTION_PATH_HINTS.any { link.contains(it, true) },
                )
            }
        }
        productDetailLinks.take(MAX_PRODUCT_DETAIL_PAGES).forEach { detailUrl ->
            if (!isPublicUrl(detailUrl) || !robotsAllows(detailUrl)) return@forEach
            val html = request(detailUrl)?.toString(Charsets.UTF_8) ?: return@forEach
            result += parseHtmlProducts(html, detailUrl, shop, distance, promotional = false)
        }
        return result.distinctBy { Triple(it.productName.lowercase(Locale.ROOT), it.price, it.productImageUrl) }
    }

    private fun candidateLinks(storeName: String, base: String, html: String): List<String> {
        return ChainSourceAdapters.forStore(storeName, base).candidateLinks(base, html)
    }

    /** Finds public catalogue/offer pages when OpenStreetMap has no website or the main site has no parsable offers. */
    private fun discoverPublicSources(storeName: String): List<String> {
        val query = URLEncoder.encode(
            "$storeName prodotti prezzi spesa online offerte volantino catalogo",
            Charsets.UTF_8.name(),
        )
        val html = request("https://html.duckduckgo.com/html/?q=$query")?.toString(Charsets.UTF_8) ?: return emptyList()
        return SEARCH_RESULT_LINK.findAll(html).mapNotNull { match ->
            val raw = match.groupValues[1].replace("&amp;", "&")
            val encodedTarget = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1)
            val target = encodedTarget?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) } ?: raw
            target.takeIf(::isPublicUrl)
        }.filterNot { url ->
            val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            host.contains("duckduckgo.com") || host.contains("facebook.com") || host.contains("instagram.com")
        }.distinct().take(MAX_DISCOVERED_SOURCES).toList()
    }

    private fun parseStructuredProducts(
        html: String,
        shop: NearbyStore,
        distance: Int,
        promotional: Boolean,
    ): List<Offer> {
        val result = mutableListOf<Offer>()
        JSON_LD.findAll(html).forEach { match ->
            val payload = runCatching { JSONObject(match.groupValues[1]) }.getOrNull() ?: return@forEach
            walkJson(payload).forEach { product ->
                val name = product.optString("name")
                val offers = product.opt("offers")
                val offer = offers as? JSONObject ?: (offers as? JSONArray)?.optJSONObject(0)
                val price = offer?.opt("price")?.toString()?.replace(',', '.')?.toDoubleOrNull()
                val image = product.opt("image")
                val imageUrl = (
                        image as? String
                            ?: if (image is JSONArray) image.optString(0)
                            else (image as? JSONObject)?.optString("url")
                    )?.takeIf { it.isNotBlank() && isPublicUrl(it) }
                if (name.isNotBlank() && price != null) {
                    result += offer(
                        name,
                        shop.name,
                        price,
                        distance,
                        productImageUrl = imageUrl,
                        storeWebsite = shop.website,
                        promotional = promotional || offer.optString("priceValidUntil").isNotBlank(),
                    )
                }
            }
        }
        return result
    }

    private fun parseHtmlProducts(
        html: String,
        pageUrl: String,
        shop: NearbyStore,
        distance: Int,
        promotional: Boolean,
    ): List<Offer> {
        val structured = parseStructuredProducts(html, shop, distance, promotional)
        val cards = HtmlProductParser.parse(html, pageUrl).map { product ->
            offer(
                name = product.name,
                store = shop.name,
                price = product.price,
                distance = distance,
                productImageUrl = product.imageUrl?.takeIf(::isPublicUrl),
                storeWebsite = shop.website,
                promotional = promotional,
                productImageVerified = product.imageUrl != null,
            )
        }
        // DOM cards keep name, price and image in the same container, so prefer them
        // over broader JSON-LD records when both describe the same product.
        return (cards + structured).distinctBy { Triple(it.productName.lowercase(Locale.ROOT), it.price, it.productImageUrl) }
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
                val embedded = PDFTextStripper().getText(document).lineSequence()
                    .map(String::trim).filter(String::isNotBlank).toList()
                parseOfferLines(embedded, shop, distance, documentIndex).ifEmpty {
                    parseOfferLines(OcrFlyerReader.read(document), shop, distance, documentIndex)
                }
            }
        }.getOrDefault(emptyList())

    private fun parseOfferLines(
        lines: List<String>,
        shop: NearbyStore,
        distance: Int,
        documentIndex: Int,
    ): List<Offer> = OfferTextParser.parse(lines).map { parsed ->
        offer(parsed.productName, shop.name, parsed.price, distance, documentIndex, storeWebsite = shop.website)
    }

    private fun offer(
        name: String,
        store: String,
        price: Double,
        distance: Int,
        salt: Int = 0,
        productImageUrl: String? = null,
        storeWebsite: String? = null,
        promotional: Boolean = true,
        productImageVerified: Boolean = false,
    ) = Offer(
        id = "$store|$name|$price|$salt".hashCode().toLong().and(0xffffffffL),
        productName = name, brand = null, storeName = store, price = price, unitPrice = null,
        distanceMeters = distance, qualityScore = null, sustainabilityLabels = emptyList(),
        validUntil = null, imageKey = imageFor(name), productImageUrl = productImageUrl, storeWebsite = storeWebsite,
        promotional = promotional,
        productImageVerified = productImageVerified,
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
        val robots = request("${uri.scheme}://${uri.authority}/robots.txt")?.toString(Charsets.UTF_8) ?: return true
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
            .filter { OfferTextParser.looksLikeProductName(it.productName) }
    }.getOrDefault(emptyList())

    private fun writeCache(offers: List<Offer>) {
        val array = JSONArray()
        offers.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("name", item.productName); put("store", item.storeName)
                put("price", item.price); put("distance", item.distanceMeters); put("image", item.imageKey.name)
                put("productImageUrl", item.productImageUrl); put("storeWebsite", item.storeWebsite)
                put("promotional", item.promotional)
                put("productImageVerified", item.productImageVerified)
            })
        }
        catalogFile.writeText(array.toString())
    }

    private fun JSONObject.toOffer(): Offer {
        val name = getString("name")
        return Offer(
            id = getLong("id"), productName = name, brand = null,
            storeName = getString("store"), price = getDouble("price"), unitPrice = null,
            distanceMeters = getInt("distance"), qualityScore = null,
            sustainabilityLabels = emptyList(), validUntil = null,
            imageKey = imageFor(name),
            productImageUrl = optString("productImageUrl").takeIf(String::isNotBlank),
            storeWebsite = optString("storeWebsite").takeIf(String::isNotBlank),
            promotional = optBoolean("promotional", true),
            productImageVerified = optBoolean("productImageVerified", false),
        )
    }

    private suspend fun migrateLegacyCacheIfNeeded() {
        if (dao.activeOffers(System.currentTimeMillis()).isNotEmpty() || !catalogFile.exists()) return
        val now = System.currentTimeMillis()
        val entities = readCache().map { offer ->
            val store = NearbyStore(
                id = StoreDeduplicator.stableId(offer.storeName, 0.0, 0.0),
                name = offer.storeName,
                category = "legacy",
                website = offer.storeWebsite,
                latitude = 0.0,
                longitude = 0.0,
                distanceMeters = offer.distanceMeters,
                sustainable = false,
            )
            offer.toEntity(store, offer.storeWebsite ?: "legacy-cache", now)
        }
        if (entities.isNotEmpty()) dao.upsertOffers(entities)
    }

    private fun NearbyStore.toEntity(now: Long) = StoreEntity(
        id = id,
        name = name,
        normalizedName = StoreDeduplicator.canonicalName(name),
        category = category,
        website = website,
        latitude = latitude,
        longitude = longitude,
        distanceMeters = distanceMeters,
        sustainable = sustainable,
        source = "OpenStreetMap",
        updatedAt = now,
    )

    private fun NearbyStore.status(state: SourceState, now: Long, count: Int, detail: String?) =
        SourceStatusEntity(
            storeId = id,
            storeName = name,
            state = state,
            sourceUrl = website,
            lastAttemptAt = now,
            lastSuccessAt = now.takeIf { state == SourceState.UPDATED },
            offerCount = count,
            detail = detail,
        )

    private fun Offer.toEntity(store: NearbyStore, sourceUrl: String, now: Long): OfferEntity {
        val fingerprint = "${store.id}|${productName.lowercase(Locale.ROOT)}|$price"
        return OfferEntity(
            id = fingerprint.hashCode().toLong().and(0xffffffffL),
            fingerprint = fingerprint,
            storeId = store.id,
            storeName = store.name,
            productName = productName,
            price = price,
            distanceMeters = store.distanceMeters,
            productImageUrl = productImageUrl,
            storeWebsite = store.website,
            sourceUrl = sourceUrl,
            parserId = ChainSourceAdapters.forStore(store.name, sourceUrl).id,
            confidence = if (productImageUrl != null) 0.92 else 0.72,
            observedAt = now,
            expiresAt = now + OFFER_TTL_MILLIS,
            promotional = promotional,
            productImageVerified = productImageVerified,
        )
    }

    private fun OfferEntity.toOffer(sustainableStore: Boolean = false) = Offer(
        id = id,
        productName = productName,
        brand = null,
        storeName = storeName,
        price = price,
        unitPrice = null,
        distanceMeters = distanceMeters,
        qualityScore = null,
        sustainabilityLabels = if (sustainableStore) listOf("Negozio locale sostenibile") else emptyList(),
        validUntil = null,
        imageKey = imageFor(productName),
        productImageUrl = productImageUrl,
        storeWebsite = storeWebsite,
        promotional = promotional,
        productImageVerified = productImageVerified,
    )

    private fun imageFor(name: String) = when {
        name.contains("ortofrutta", true) -> ProductImageKey.PRODUCE
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
        const val MAX_DOCUMENTS_PER_STORE = 6
        const val MAX_SOURCE_PAGES_PER_STORE = 3
        const val MAX_DISCOVERED_SOURCES = 3
        const val MAX_PRODUCT_DETAIL_PAGES = 12
        const val MAX_DOCUMENT_BYTES = 15 * 1024 * 1024
        const val OFFER_TTL_MILLIS = 14L * 24 * 60 * 60 * 1_000
        val JSON_LD = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val SEARCH_RESULT_LINK = Regex("""href=["']([^"']*(?:uddg=|https?%3A%2F%2F)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val PROMOTION_PATH_HINTS = setOf("offert", "promo", "volantin", "scont")
    }
}
