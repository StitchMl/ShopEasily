@file:Suppress("SpellCheckingInspection")

package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import it.lagioiaproductions.shopeasily.data.local.OfferEntity
import it.lagioiaproductions.shopeasily.data.local.ShopEasilyDatabase
import it.lagioiaproductions.shopeasily.data.local.SourceState
import it.lagioiaproductions.shopeasily.data.local.SourceStatusEntity
import it.lagioiaproductions.shopeasily.data.local.StoreEntity
import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import it.lagioiaproductions.shopeasily.data.model.Store
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import it.lagioiaproductions.shopeasily.data.repository.net.NonShopSites
import it.lagioiaproductions.shopeasily.data.repository.parsers.CatalogSanitizer
import it.lagioiaproductions.shopeasily.data.repository.parsers.ChainSourceAdapters
import it.lagioiaproductions.shopeasily.data.repository.parsers.HtmlProductParser
import it.lagioiaproductions.shopeasily.data.repository.parsers.OcrFlyerReader
import it.lagioiaproductions.shopeasily.data.repository.parsers.OfferTextParser
import it.lagioiaproductions.shopeasily.BuildConfig
import it.lagioiaproductions.shopeasily.data.repository.sources.EcommerceApiSource
import it.lagioiaproductions.shopeasily.data.repository.sources.FarmerMarketSource
import it.lagioiaproductions.shopeasily.data.repository.sources.GooglePlacesSource
import it.lagioiaproductions.shopeasily.data.repository.sources.GooglePlace
import it.lagioiaproductions.shopeasily.data.repository.sources.OpenPricesSource
import it.lagioiaproductions.shopeasily.data.repository.sources.SourceProduct
import it.lagioiaproductions.shopeasily.domain.ProductMatcher
import it.lagioiaproductions.shopeasily.domain.StoreSustainability
import it.lagioiaproductions.shopeasily.domain.StoreSustainabilityResult
import it.lagioiaproductions.shopeasily.domain.effectiveQualityScore
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class NearbyStore(
    val id: String = "",
    val name: String,
    val category: String,
    val website: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val sustainable: Boolean,
    val searchContext: String = "",
    val sustainabilityScore: Int = 0,
    val sustainabilityReasons: List<String> = emptyList(),
    val osmType: String? = null,
    val osmId: Long? = null,
    /** Retail brand from OSM `brand` tag (or a name shared by several shops); null for independents. */
    val brand: String? = null,
    val brandWikidata: String? = null,
    val place: String? = null,
    val reviewRating: Double? = null,
    val reviewCount: Int = 0,
    val priceLevel: String? = null,
)

class OnDeviceCatalogRepository(
    context: Context,
    private val fallback: OffersRepository = FakeOffersRepository(),
) : OffersRepository {
    private val appContext = context.applicationContext
    private val catalogFile = appContext.filesDir.resolve("scraped_catalog.json")
    private val dao = ShopEasilyDatabase.get(appContext).catalogDao()
    private val routing = RoutingRepository()
    private val http = HttpFetcher(USER_AGENT)
    private val brands = BrandDirectory.get(appContext)
    private val googlePlaces = GooglePlacesSource(
        BuildConfig.GOOGLE_PLACES_API_KEY,
        http,
        androidPackage = appContext.packageName,
        androidCertSha1 = signingCertSha1(appContext),
    )
    private val farmerMarkets = FarmerMarketSource(http)

    fun observeSourceStatuses(): Flow<List<SourceStatusEntity>> = dao.observeSourceStatuses()

    /** Stores still waiting after the last [synchronize] call: the worker schedules another run. */
    @Volatile var remainingAfterRun: Int = 0
        private set

    /** Emits whenever offers change (background sync, enrichment): drives silent UI refreshes. */
    fun observeCatalogVersion(): Flow<Long> = dao.observeCatalogVersion()

    suspend fun storedStores(): List<NearbyStore> =
        dao.stores().filterNot { NonShopSites.isPlatformName(it.name) }.also(::registerBrands).map { it.toNearbyStore() }

    private fun registerBrands(stores: List<StoreEntity>) {
        BrandDirectory.rememberWikidata(stores.mapNotNull { store -> store.brandWikidata?.let { store.name to it } }.toMap())
        StoreDeduplicator.learnFromNames(stores.map(StoreEntity::name), stores.mapNotNull(StoreEntity::place))
        StoreDeduplicator.registerBrands(stores.mapNotNull { store -> store.brand?.let { BrandRecord(store.name, it) } })
        // Brands sharing their official site are grouped (e.g. Oasi + Tigre → oasitigre.it).
        val hosts = stores.filter { StoreDeduplicator.brandOf(it.name) != null }
            .groupBy { StoreDeduplicator.rootKey(it.name) }
            .mapNotNull { (key, branches) ->
                val host = brands.cached(StoreDeduplicator.rootDisplayName(branches.first().name))?.website?.let(::hostOf)
                    ?: branches.mapNotNull { it.website?.let(::hostOf) }
                        .filterNot { host -> AGGREGATOR_HOSTS.any(host::contains) }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                host?.takeIf(String::isNotBlank)?.let { key to it }
            }.toMap()
        StoreDeduplicator.registerSiteGroups(hosts)
    }

    /**
     * Places where the user can report a price seen on the shelf: markets and small
     * shops first (they rarely publish prices online), then the others, by distance.
     */
    suspend fun priceReportStores(): List<NearbyStore> = withContext(Dispatchers.IO) {
        storedStores().sortedWith(
            compareBy<NearbyStore> { store ->
                when {
                    store.category == "marketplace" || store.category == "farm" -> 0
                    StoreDeduplicator.brandOf(store.name) == null -> 1
                    else -> 2
                }
            }.thenBy(NearbyStore::distanceMeters),
        ).take(MAX_REPORT_STORES)
    }

    /**
     * Saves a price the user saw in a shop or at a market stall (e.g. Campagna Amica,
     * a municipal market): these places publish no prices online, so the user's own
     * observation is the reliable source. [quality] 1..5 feeds the Quality sort.
     */
    suspend fun addUserPrice(storeId: String, productName: String, price: Double, quality: Int?): Boolean =
        withContext(Dispatchers.IO) {
            val store = dao.store(storeId) ?: return@withContext false
            val name = productName.trim().replace(Regex("\\s+"), " ")
            if (name.length < 2 || price <= 0.0 || price > 1_000.0) return@withContext false
            val now = System.currentTimeMillis()
            val fingerprint = "${store.id}|${name.lowercase(Locale.ROOT)}|user"
            dao.upsertOffers(
                listOf(
                    OfferEntity(
                        id = fingerprint.hashCode().toLong().and(0xffffffffL),
                        fingerprint = fingerprint,
                        storeId = store.id,
                        storeName = store.name,
                        productName = name,
                        price = price,
                        distanceMeters = store.distanceMeters,
                        productImageUrl = null,
                        storeWebsite = store.website,
                        sourceUrl = "user://report/${store.id}",
                        parserId = USER_PARSER_ID,
                        confidence = 1.0,
                        observedAt = now,
                        expiresAt = now + USER_PRICE_TTL_MS,
                        promotional = false,
                        productImageVerified = false,
                        labels = null,
                        userQuality = quality?.coerceIn(1, 5),
                    ),
                ),
            )
            true
        }

    private fun knownBrands(stores: Collection<StoreEntity>): Set<String> =
        stores.mapNotNull { it.brand?.let(StoreDeduplicator::canonicalName) }.filter { it.length >= 3 }.toSet()

    suspend fun offersByIds(ids: Collection<Long>): List<Offer> {
        if (ids.isEmpty()) return emptyList()
        val stores = dao.stores().associateBy(StoreEntity::id)
        return ids.chunked(500).flatMap { chunk -> dao.offersByIds(chunk) }.map { it.toOffer(stores[it.storeId]) }
    }

    suspend fun catalogPrices(): List<CatalogPrice> = withContext(Dispatchers.IO) {
        val stores = dao.stores().filterNot { NonShopSites.isPlatformName(it.name) }.associateBy(StoreEntity::id)
        dao.activeOffers(System.currentTimeMillis()).mapNotNull { entity ->
            val store = stores[entity.storeId] ?: return@mapNotNull null
            val offer = entity.toOffer(store)
            val labels = offer.sustainabilityLabels.joinToString(" ").lowercase(Locale.ROOT)
            CatalogPrice(
                store = Store(
                    id = store.id.hashCode().toLong().and(0xffffffffL),
                    name = store.name,
                    channel = StoreChannel.PHYSICAL,
                    latitude = store.latitude,
                    longitude = store.longitude,
                    distanceMeters = store.distanceMeters,
                ),
                productName = entity.productName,
                aliases = setOf(entity.productName.lowercase(Locale.ROOT)),
                price = entity.price,
                promotional = entity.promotional,
                qualityScore = offer.effectiveQualityScore().toDouble(),
                ecological = store.sustainabilityScore >= StoreSustainabilityResult.LEAF_THRESHOLD ||
                    labels.contains("biolog") || labels.contains("filiera"),
                fairTrade = labels.contains("equo") || store.sustainabilityReasons.orEmpty().contains("equo", true),
            )
        }
    }

    override fun search(query: String): Flow<List<Offer>> = flow {
        migrateLegacyCacheIfNeeded()
        val storeList = dao.stores().filterNot { NonShopSites.isPlatformName(it.name) }.also(::registerBrands)
        val stores = storeList.associateBy(StoreEntity::id)
        // Offers of a store that is no longer valid (e.g. "Meta", a social page) are hidden.
        val cached = dao.activeOffers(System.currentTimeMillis())
            .filter { it.storeId in stores && !NonShopSites.isPlatformName(it.storeName) }
        // Implausible rows are only hidden, never deleted: a rule change must not destroy data.
        val invalidIdSet = cached.filterNot {
            it.parserId == USER_PARSER_ID || CatalogSanitizer.isPlausible(it.productName, it.price, it.storeName, it.sourceUrl, it.productImageUrl)
        }.mapTo(HashSet(), OfferEntity::id)
        val local = cached.asSequence().filter { it.id !in invalidIdSet }.map { it.toOffer(stores[it.storeId]) }
            .filter { offer ->
                query.isBlank() || ProductMatcher.matches(offer.productName, query) ||
                    offer.storeName.contains(query, true)
            }.toList()
        val demo = if (local.isEmpty() && cached.isEmpty()) fallback.search(query).first() else emptyList()
        emit((local + demo).distinctBy { Triple(it.storeName, it.productName, it.price) }.distinctBy(Offer::id))
    }.flowOn(Dispatchers.IO)

    /**
     * Completes a user search with ordinary shelf prices from public product
     * pages of nearby retailers. Query-driven and parallel (4 stores at a time).
     */
    suspend fun enrichRegularPrices(query: String): Int = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().takeIf { it.length >= 2 } ?: return@withContext 0
        val now = System.currentTimeMillis()
        val current = dao.activeOffers(now)
        val stores = storedStores().sortedBy(NearbyStore::distanceMeters).take(MAX_QUERY_STORES)
        val imported = AtomicInteger(0)
        val permits = Semaphore(PARALLEL_STORES)
        coroutineScope {
            stores.map { shop ->
                async {
                    permits.withPermit {
                        if (current.any { it.storeId == shop.id && ProductMatcher.matches(it.productName, normalizedQuery) }) {
                            return@withPermit
                        }
                        val website = shop.website?.let(HttpFetcher::secureUrl) ?: return@withPermit
                        for (sourceUrl in productSearchLinks(website, normalizedQuery)) {
                            ensureActive()
                            val html = http.politeGet(sourceUrl)?.toString(Charsets.UTF_8) ?: continue
                            val offers = parseHtmlProducts(html, sourceUrl, shop, shop.distanceMeters, promotional = false)
                                .filter { ProductMatcher.matches(it.productName, normalizedQuery) }
                                .distinctBy { it.productName.lowercase(Locale.ROOT) to it.price }
                            if (offers.isNotEmpty()) {
                                dao.upsertOffers(offers.map { it.toEntity(shop, sourceUrl, now) })
                                imported.addAndGet(offers.size)
                                break
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        imported.get()
    }

    private fun productSearchLinks(website: String, query: String): List<String> {
        val base = runCatching { URI(website) }.getOrNull() ?: return emptyList()
        val host = base.host ?: return emptyList()
        val origin = "https://$host"
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val direct = listOf(
            "$origin/search?q=$encoded",
            "$origin/?s=$encoded&post_type=product",
            "$origin/catalogsearch/result/?q=$encoded",
        )
        val webQuery = URLEncoder.encode("site:$host $query prezzo", Charsets.UTF_8.name())
        val indexed = http.getText("https://html.duckduckgo.com/html/?q=$webQuery")
            ?.let(::searchResultUrls)
            .orEmpty()
            .filter { runCatching { URI(it).host.equals(host, true) }.getOrDefault(false) }
        return (indexed + direct).distinct().take(MAX_QUERY_PAGES)
    }

    private fun searchResultUrls(html: String): List<String> = SEARCH_RESULT_LINK.findAll(html).mapNotNull { match ->
        val raw = match.groupValues[1].replace("&amp;", "&")
        val encodedTarget = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1)
        (encodedTarget?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() } ?: raw)
            .takeIf { it.startsWith("http") }
    }.distinct().toList()

    /**
     * Refreshes stores and offers around a position. Designed to run inside
     * [it.lagioiaproductions.shopeasily.sync.CatalogSyncWorker]: it is
     * incremental (stores refreshed recently are skipped unless [force]),
     * parallel, bounded in memory, and never throws for network problems.
     */
    suspend fun synchronize(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        force: Boolean = false,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // The list of shops changes slowly: reuse it for 7 days unless the user moved > 1 km
        // or changed radius (the OpenStreetMap query alone takes 5-20 s in a big city).
        val area = appContext.getSharedPreferences("store_list_area_v2", Context.MODE_PRIVATE)
        val lastLat = area.getFloat("lat", Float.NaN).toDouble()
        val lastLon = area.getFloat("lon", Float.NaN).toDouble()
        val sameArea = !lastLat.isNaN() && area.getInt("radius", -1) == radiusKm &&
            distanceMeters(lastLat, lastLon, latitude, longitude) < 1_000 &&
            now - area.getLong("at", 0L) < STORE_LIST_TTL_MS
        val stored = storedStores()
        val known = if (sameArea && !force) stored.filter { it.distanceMeters <= radiusKm * 1_000 } else emptyList()
        val discovered: List<NearbyStore> = if (known.isNotEmpty()) {
            // OSM geometry can be cached for a week, but branch identities cannot: banners
            // change and Google may already expose the new official name. Reconcile the
            // cached stores independently (the Places source has its own 3-day throttle).
            // Also reconcile previously discovered branches whose official domain no
            // longer agrees with the saved banner. They may sit just outside the current
            // shopping radius but still be visible in the cached map.
            val identityCandidates = (known + stored.filter(::officialDomainDisagreesWithName))
                .distinctBy(NearbyStore::id)
            runCatching { mergeGooglePlaces(identityCandidates, latitude, longitude, radiusKm * 1_000) }
                .getOrDefault(identityCandidates)
        } else {
            runCatching { nearbyStores(latitude, longitude, radiusKm, includeGooglePlaces = true) }.getOrDefault(emptyList())
                .also { stores ->
                    if (stores.isNotEmpty()) {
                        area.edit().putFloat("lat", latitude.toFloat()).putFloat("lon", longitude.toFloat())
                            .putInt("radius", radiusKm).putLong("at", now).apply()
                    }
                }
                .map { store -> withBrandInfo(store, now) }
        }
        val shops = discovered
            .filter { it.distanceMeters <= radiusKm * 1_000 }
            .ifEmpty { known }
            .ifEmpty {
            // Overpass unavailable (rate limit/offline): keep working on the stores already known.
            stored.filter { it.distanceMeters <= radiusKm * 1_000 }
            }
        if (discovered.isNotEmpty()) {
            // A website found earlier (Google Places, brand lookup) is kept when OSM has none.
            val previous = dao.stores().associateBy(StoreEntity::id)
            dao.upsertStores(discovered.map { store ->
                val kept = store.website ?: previous[store.id]?.website?.let(NonShopSites::shopWebsiteOrNull)
                store.copy(website = kept).toEntity(now)
            })
        }
        dao.deleteExpired(now)
        dao.deleteLegacyOpenPricesOffers()
        val storeEntities = dao.stores().filterNot { NonShopSites.isPlatformName(it.name) }.also(::registerBrands)
        val brandSet = knownBrands(storeEntities)

        val statuses = dao.sourceStatuses().associateBy(SourceStatusEntity::storeId)
        // After an app update every store is retried: new readers may now find its prices.
        val appUpdatedAt = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        // Pick the stores that need a refresh FIRST, then the nearest of them: taking the
        // nearest 80 first meant that in a city every store beyond the 80th was never read.
        val due = shops.filter { shop ->
            force || statuses[shop.id].isStale(now) || (statuses[shop.id]?.lastAttemptAt ?: 0L) < appUpdatedAt
        }.sortedWith(
            // Never-read stores first, then by distance.
            compareBy<NearbyStore> { if (statuses[it.id] == null) 0 else 1 }.thenBy(NearbyStore::distanceMeters),
        )
        val prioritized = due.take(MAX_SYNC_STORES)
        remainingAfterRun = (due.size - prioritized.size).coerceAtLeast(0)
        val completed = AtomicInteger(0)
        val imported = AtomicInteger(0)
        val scanCache = SharedScanCache()
        val total = prioritized.size * 2
        // Pass 1 (fast, 8 in parallel): quick sources only, so Home fills within seconds.
        // Pass 2 (deep, 4 in parallel): PDFs/OCR, sitemaps, web search, markets.
        for ((deep, parallel) in listOf(false to PARALLEL_FAST, true to PARALLEL_STORES)) {
            val permits = Semaphore(parallel)
            coroutineScope {
                prioritized.map { shop ->
                    async {
                        permits.withPermit {
                            ensureActive()
                            imported.addAndGet(syncShop(shop, latitude, longitude, now, scanCache, brandSet, this@coroutineScope, deep))
                            onProgress(completed.incrementAndGet(), total)
                        }
                    }
                }.awaitAll()
            }
        }
        imported.get()
    }

    private fun SourceStatusEntity?.isStale(now: Long): Boolean {
        if (this == null) return true
        val age = now - lastAttemptAt
        return when (state) {
            SourceState.UPDATED -> age > REFRESH_UPDATED_AFTER_MS
            SourceState.PENDING -> age > 30 * 60_000L
            else -> age > RETRY_FAILED_AFTER_MS
        }
    }

    private suspend fun syncShop(
        shop: NearbyStore,
        userLat: Double,
        userLon: Double,
        now: Long,
        scanCache: SharedScanCache,
        brandSet: Set<String>,
        scope: CoroutineScope,
        deep: Boolean = true,
    ): Int {
        dao.upsertStatus(shop.status(SourceState.PENDING, now, 0, null))
        return try {
            // Chains: official site, logo and flyer pages are discovered automatically
            // (OSM brand:wikidata → Wikidata, or web search), once per brand.
            // Independent shops without a website are looked up too (e.g. "Supermercato Elite" → superelite.it).
            val chainBrand = shop.brand ?: StoreDeduplicator.brandOf(shop.name)
            val independentName = shop.name.takeIf {
                chainBrand == null && shop.brandWikidata == null && shop.website == null && !StoreDeduplicator.isGenericName(it)
            }
            val lookupName = chainBrand ?: shop.name.takeIf { shop.brandWikidata != null } ?: independentName
            // Independent shops: the address is required to accept a website (namesakes elsewhere).
            val locationHint = if (independentName != null && lookupName == independentName) {
                listOfNotNull(shop.searchContext.takeIf(String::isNotBlank), shop.place).joinToString(" ").ifBlank { null }
            } else {
                null
            }
            // Independent shop without site: ask Google Places for THIS shop (name + exact position).
            val placesWebsite = if (deep && shop.website == null && independentName != null && googlePlaces.isEnabled) {
                runCatching { googlePlaces.findWebsite(shop.name, shop.latitude, shop.longitude) }.getOrNull()
                    ?.let(NonShopSites::shopWebsiteOrNull)
            } else {
                null
            }
            // Looking up an independent shop's site (domain guesses, web search) is slow: deep pass only.
            val brandInfo = lookupName?.takeIf { independentName == null || (locationHint != null && deep && placesWebsite == null) }?.let { name ->
                runCatching { brands.resolve(name, shop.brandWikidata, now, locationHint) }.getOrNull()
            }
            val website = NonShopSites.shopWebsiteOrNull(shop.website)?.let(HttpFetcher::secureUrl)
                ?: placesWebsite?.let(HttpFetcher::secureUrl)
                ?: NonShopSites.shopWebsiteOrNull(brandInfo?.website)
            val directCatalogs = brandInfo?.flyerUrls.orEmpty()
            val scored = brandInfo?.description?.let { description -> applyBrandDescription(shop, description, now) } ?: shop
            if (website != shop.website || scored !== shop) {
                dao.upsertStores(listOf(scored.copy(website = website).toEntity(now)))
            }
            val offers = mutableListOf<Offer>()
            // Human-readable trace of what was tried, shown in "Fonti dei prezzi".
            val trace = mutableListOf<String>()
            if (placesWebsite != null) trace += "sito da Google Maps: ${hostOf(placesWebsite)}"
            if (lookupName != null) {
                trace += "insegna: " + (brandInfo?.website?.let(::hostOf) ?: "sito non trovato") +
                    (if (directCatalogs.isNotEmpty()) ", volantini ${directCatalogs.size}" else "")
            }

            // 1. Open Prices: crowdsourced price tags linked to this exact OSM shop.
            if (shop.osmType != null && shop.osmId != null) {
                val locationUrl = OpenPricesSource.locationUrl(shop.osmType, shop.osmId)
                val locationId = http.getText(locationUrl, HttpFetcher.MAX_JSON_BYTES, accept = "application/json")
                    ?.let(OpenPricesSource::locationId)
                if (locationId != null) {
                    val pricesUrl = OpenPricesSource.pricesUrl(locationId, now)
                    val before = offers.size
                    http.getText(pricesUrl, HttpFetcher.MAX_JSON_BYTES, accept = "application/json")
                        ?.let { OpenPricesSource.parse(it, pricesUrl) }
                        ?.mapTo(offers) { it.toOffer(shop) }
                    trace += "Open Prices ${offers.size - before}"
                } else {
                    trace += "Open Prices 0"
                }
            }

            // 2. Farmers' markets: their online shop in the Campagna Amica network (real prices).
            val marketSites = if (deep && FarmerMarketSource.isFarmerMarket(shop.name, shop.category)) {
                runCatching {
                    farmerMarkets.shopSites(shop.name, listOfNotNull(shop.searchContext, shop.place).joinToString(" "))
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            for (site in marketSites) {
                val read = scanCache.get("market|${hostOf(site)}", scope) { ecommerceProducts(site) + scanShop(shop.copy(website = site), userLat, userLon) }
                    .map { it.copy(storeName = shop.name, distanceMeters = shop.distanceMeters, storeWebsite = site) }
                offers += read
                trace += "mercato online ${hostOf(site)} ${read.size}"
            }

            // 3. Public e-commerce feeds (WooCommerce/Shopify) used by many small shops.
            if (website != null && directCatalogs.isEmpty()) {
                val before = offers.size
                offers += scanCache.get("api|${hostOf(website)}", scope) { ecommerceProducts(website) }
                    .map { it.copy(storeName = shop.name, distanceMeters = shop.distanceMeters) }
                if (offers.size > before) trace += "e-commerce ${offers.size - before}"
            }

            // 4. Flyers, JSON-LD, product cards and PDFs.
            val sources = (directCatalogs + listOfNotNull(website)).distinct().take(MAX_SOURCE_PAGES_PER_STORE)
            fun scanKey(source: String) = "${StoreDeduplicator.brandKey(shop.name)}|$source|$deep"
            for (source in sources) {
                val read = scanCache.get(scanKey(source), scope) {
                    scanShop(shop.copy(website = source), userLat, userLon, deep)
                }.map { it.copy(storeName = shop.name, distanceMeters = shop.distanceMeters, storeWebsite = source) }
                offers += read
                trace += "${hostOf(source)} ${read.size}"
            }
            if (offers.isEmpty() && deep) {
                val discovered = discoverPublicSources(
                    shop.name,
                    listOfNotNull(shop.searchContext.takeIf(String::isNotBlank), shop.place).joinToString(" "),
                )
                    .filterNot(sources::contains).take(MAX_DISCOVERED_SOURCES)
                for (source in discovered) {
                    offers += scanCache.get(scanKey(source), scope) {
                        scanShop(shop.copy(website = source), userLat, userLon, deep)
                    }.map { it.copy(storeName = shop.name, distanceMeters = shop.distanceMeters, storeWebsite = source) }
                }
                trace += "ricerca web ${discovered.size} pagine"
            }

            val entities = offers
                .distinctBy { Triple(it.productName.lowercase(Locale.ROOT), it.price, it.storeName) }
                .filter { CatalogSanitizer.isPlausible(it.productName, it.price, shop.name, it.storeWebsite.orEmpty(), it.productImageUrl, brandSet) }
                .take(MAX_OFFERS_PER_STORE)
                .map { it.toEntity(shop, it.storeWebsite ?: website ?: "osm", now) }
            if (entities.isNotEmpty()) {
                // Replace only when something was read: a temporary failure must not wipe good data.
                dao.replaceStoreOffers(shop.id, entities)
            }
            val state = when {
                entities.isNotEmpty() -> SourceState.UPDATED
                // Fast pass found nothing yet: the deep pass will decide.
                !deep -> SourceState.PENDING
                website == null && shop.osmId == null && directCatalogs.isEmpty() -> SourceState.UNAVAILABLE
                else -> SourceState.NO_OFFERS
            }
            val detail = trace.joinToString(" · ").ifBlank {
                if (state == SourceState.UNAVAILABLE) "Nessun sito né prezzi condivisi" else "Nessun prezzo pubblico leggibile"
            }.take(300)
            dao.upsertStatus(shop.copy(website = website).status(state, now, entities.size, detail))
            entities.size
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            dao.upsertStatus(shop.status(SourceState.UNAVAILABLE, now, 0, error.javaClass.simpleName))
            0
        }
    }

    private fun ecommerceProducts(website: String): List<Offer> {
        val placeholder = NearbyStore(name = "", category = "", website = website, latitude = 0.0, longitude = 0.0, distanceMeters = 0, sustainable = false)
        for ((parserId, url) in EcommerceApiSource.candidateUrls(website)) {
            val json = http.politeGet(url, HttpFetcher.MAX_JSON_BYTES, accept = "application/json")
                ?.toString(Charsets.UTF_8) ?: continue
            if (!json.trimStart().startsWith("[") && !json.trimStart().startsWith("{")) continue
            val products = EcommerceApiSource.parse(parserId, json, url)
            if (products.isNotEmpty()) return products.map { it.toOffer(placeholder) }
        }
        return emptyList()
    }

    /** Deduplicates concurrent scans of the same chain catalogue shared by many branches. */
    private class SharedScanCache {
        private val mutex = Mutex()
        private val entries = HashMap<String, Deferred<List<Offer>>>()

        suspend fun get(key: String, scope: CoroutineScope, block: () -> List<Offer>): List<Offer> {
            val deferred = mutex.withLock {
                entries.getOrPut(key) { scope.async(Dispatchers.IO) { runCatching(block).getOrDefault(emptyList()) } }
            }
            return deferred.await()
        }
    }

    suspend fun nearbyStores(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        includeGooglePlaces: Boolean = false,
    ): List<NearbyStore> =
        withContext(Dispatchers.IO) {
            val radius = (radiusKm * 1_000).coerceIn(500, 20_000)
            val osm = discoverShops(latitude, longitude, radius)
            val stores = if (includeGooglePlaces) mergeGooglePlaces(osm, latitude, longitude, radius) else osm
            if (stores.isEmpty()) return@withContext emptyList()
            val roadDistances = routing.distancesFrom(
                RoutePoint(latitude, longitude),
                stores.map { RoutePoint(it.latitude, it.longitude) },
            )
            stores.mapIndexed { index, store ->
                store.copy(distanceMeters = roadDistances.getOrNull(index) ?: store.distanceMeters)
            }.sortedBy(NearbyStore::distanceMeters)
        }

    /**
     * Adds shops known to Google Maps (official Places API, only with the developer's key):
     * a place near an OSM shop with a matching name gives it its website; a place missing
     * from OSM becomes a new store. Queried at most every 3 days per area to limit API cost.
     */
    private suspend fun mergeGooglePlaces(osm: List<NearbyStore>, latitude: Double, longitude: Double, radius: Int): List<NearbyStore> {
        if (!googlePlaces.isEnabled) return osm
        val previous = dao.stores().associateBy(StoreEntity::id)
        val result = osm.map { store ->
            val saved = previous[store.id]
            store.copy(
                name = saved?.name ?: store.name,
                // A previously verified official branch page is stronger than a stale
                // OSM website tag and is also the signal used to detect renamed banners.
                website = saved?.website?.let(NonShopSites::shopWebsiteOrNull) ?: store.website,
                reviewRating = saved?.reviewRating,
                reviewCount = saved?.reviewCount ?: 0,
                priceLevel = saved?.priceLevel,
            )
        }.toMutableList()
        // Versioned because identity reconciliation changed: run one fresh lookup after update.
        val preferences = appContext.getSharedPreferences("google_places_v3", Context.MODE_PRIVATE)
        val area = "${(latitude * 100).roundToInt()}|${(longitude * 100).roundToInt()}|$radius"
        val cachedAt = preferences.getLong(area, 0L)
        val nearbyPlaces = if (System.currentTimeMillis() - cachedAt < GOOGLE_REFRESH_MS) {
            emptyList()
        } else {
            runCatching { googlePlaces.nearbyShops(latitude, longitude, radius) }.getOrDefault(emptyList())
                .also { if (it.isNotEmpty()) preferences.edit().putLong(area, System.currentTimeMillis()).apply() }
        }
        // Nearby Search is capped in dense cities. If an already resolved official domain
        // disagrees with the saved banner, verify that exact branch through Text Search.
        val hostSupport = result.groupingBy { store -> store.website?.let(::hostOf).orEmpty() }
            .fold(0) { count, store ->
                val hostTokens = store.website?.let(::hostOf)?.replace('.', ' ')
                    ?.let(StoreDeduplicator::meaningfulTokens).orEmpty().toSet()
                val nameTokens = StoreDeduplicator.meaningfulTokens(store.name).toSet()
                count + if (hostTokens.any(nameTokens::contains)) 1 else 0
            }
        val targeted = result.asSequence()
            .filter(::officialDomainDisagreesWithName)
            .filter { store ->
                System.currentTimeMillis() - preferences.getLong("identity-v2:${store.id}", 0L) >= GOOGLE_REFRESH_MS
            }
            // A domain consistently used by many correctly named branches is strong
            // evidence of a stale banner and takes precedence over mere proximity.
            .sortedWith(
                compareByDescending<NearbyStore> { store -> hostSupport[store.website?.let(::hostOf).orEmpty()] ?: 0 }
                    .thenBy(NearbyStore::distanceMeters),
            )
            .take(MAX_TARGETED_IDENTITY_LOOKUPS)
            .mapNotNull { store ->
                runCatching { googlePlaces.findPlace(store.name, store.latitude, store.longitude) }.getOrNull()
                    ?.also {
                        // A timeout or empty answer must remain retryable; throttle only
                        // identities that were actually resolved and validated.
                        preferences.edit().putLong("identity-v2:${store.id}", System.currentTimeMillis()).apply()
                    }
            }
            .toList()
        val places = (nearbyPlaces + targeted).distinctBy(GooglePlace::id)
        if (places.isEmpty()) return result
        places.filterNot { NonShopSites.isPlatformName(it.name) }.forEach { place ->
            val identity = StoreIdentityMatcher.match(result, place)
            if (identity != null) {
                val store = result[identity.index]
                val site = NonShopSites.shopWebsiteOrNull(place.website)
                result[identity.index] = store.copy(
                    name = place.name.takeIf { identity.adoptSourceName } ?: store.name,
                    website = store.website ?: site?.let(HttpFetcher::secureUrl),
                    reviewRating = place.rating ?: store.reviewRating,
                    reviewCount = maxOf(store.reviewCount, place.reviewCount),
                    priceLevel = place.priceLevel ?: store.priceLevel,
                )
            } else {
                val category = GooglePlacesSource.category(place.types)
                val sustainability = StoreSustainability.evaluate(place.name, category, GooglePlacesSource.sustainabilityTags(place))
                result += NearbyStore(
                    id = StoreDeduplicator.stableId(place.name, place.latitude, place.longitude),
                    name = place.name,
                    category = category,
                    website = NonShopSites.shopWebsiteOrNull(place.website)?.let(HttpFetcher::secureUrl),
                    latitude = place.latitude,
                    longitude = place.longitude,
                    distanceMeters = distanceMeters(latitude, longitude, place.latitude, place.longitude),
                    sustainable = sustainability.hasLeaf,
                    searchContext = place.address.orEmpty(),
                    sustainabilityScore = sustainability.score,
                    sustainabilityReasons = sustainability.reasons,
                    place = place.address,
                    reviewRating = place.rating,
                    reviewCount = place.reviewCount,
                    priceLevel = place.priceLevel,
                )
            }
        }
        return result
    }

    private fun officialDomainDisagreesWithName(store: NearbyStore): Boolean {
        val website = store.website ?: return false
        val hostTokens = StoreDeduplicator.meaningfulTokens(hostOf(website).replace('.', ' ')).toSet()
        val nameTokens = StoreDeduplicator.meaningfulTokens(store.name).toSet()
        return hostTokens.isNotEmpty() && nameTokens.isNotEmpty() && hostTokens.none(nameTokens::contains)
    }

    private fun discoverShops(latitude: Double, longitude: Double, radius: Int): List<NearbyStore> {
        val around = "around:$radius,$latitude,$longitude"
        val query = """[out:json][timeout:25];(
          nwr($around)[shop~"^(supermarket|convenience|discount|deli|butcher|greengrocer|bakery|farm|organic|health_food|dairy|cheese|seafood|pastry|general|food|zero_waste)$"];
          nwr($around)[amenity="marketplace"];
          nwr($around)[shop][bulk_purchase~"^(yes|only)$"];
        );out center tags;""".trimIndent()
        val body = ("data=" + URLEncoder.encode(query, Charsets.UTF_8.name())).toByteArray()
        val response = OVERPASS_URLS.firstNotNullOfOrNull { endpoint ->
            // A 10 km radius in a big city returns thousands of shops: allow a large answer.
            http.post(endpoint, body, MAX_OVERPASS_BYTES)?.toString(Charsets.UTF_8)
                ?.takeIf { it.trimStart().startsWith("{") }
        } ?: return emptyList()
        val elements = runCatching { JSONObject(response).optJSONArray("elements") }.getOrNull() ?: return emptyList()
        data class Raw(val item: JSONObject, val tags: Map<String, String>, val name: String, val brandKey: String)
        fun placesOf(raw: Raw): List<String> = listOfNotNull(
            raw.tags["addr:city"], raw.tags["addr:suburb"], raw.tags["addr:quarter"], raw.tags["addr:neighbourhood"],
            raw.tags["addr:place"], raw.tags["is_in:city"],
        ).filter(String::isNotBlank)
        val raws = (0 until elements.length()).mapNotNull { index ->
            val item = elements.optJSONObject(index) ?: return@mapNotNull null
            val tagsJson = item.optJSONObject("tags") ?: return@mapNotNull null
            val tags = tagsJson.keys().asSequence().associateWith { tagsJson.optString(it) }
            val name = tags["name"].orEmpty()
            if (name.isBlank() || NonShopSites.isPlatformName(name)) return@mapNotNull null
            val brandName = tags["brand"]?.takeIf(String::isNotBlank) ?: name
            Raw(item, tags, name, StoreDeduplicator.canonicalName(brandName))
        }
        // A brand is recognised automatically: OSM `brand`/`brand:wikidata` tags, or words
        // shared by several shop names in the area. Branches share their public tags.
        StoreDeduplicator.learnFromNames(raws.map(Raw::name), raws.flatMap(::placesOf))
        StoreDeduplicator.registerBrands(raws.mapNotNull { raw -> raw.tags["brand"]?.takeIf(String::isNotBlank)?.let { BrandRecord(raw.name, it) } })
        val groups = raws.groupBy { StoreDeduplicator.brandKey(it.name) }
        val inherited = groups.mapValues { (_, branches) ->
            StoreSustainability.INHERITABLE_TAGS.mapNotNull { key ->
                StoreSustainability.strongest(branches.map { it.tags[key] })?.let { key to it }
            }.toMap()
        }
        val brandWebsites = groups.mapValues { (_, branches) ->
            branches.firstNotNullOfOrNull { it.tags["brand:website"] ?: it.tags["website"] ?: it.tags["contact:website"] }
        }
        val brandWikidata = groups.mapValues { (_, branches) -> branches.firstNotNullOfOrNull { it.tags["brand:wikidata"] } }
        return raws.mapNotNull { raw ->
            val center = raw.item.optJSONObject("center") ?: raw.item
            if (!center.has("lat") || !center.has("lon")) return@mapNotNull null
            val tags = raw.tags
            val groupKey = StoreDeduplicator.brandKey(raw.name)
            val branchCount = groups[groupKey]?.size ?: 1
            // Only the brand declared in OpenStreetMap is stored; brands deduced from names
            // are recomputed at runtime (StoreDeduplicator.brandOf), so a wrong guess never sticks.
            val brand = tags["brand"]?.takeIf { it.isNotBlank() && !StoreDeduplicator.isGenericName(it) }
            val isChain = brand != null || StoreDeduplicator.brandOf(raw.name) != null ||
                (!StoreDeduplicator.isGenericName(raw.name) && branchCount >= MIN_BRANCHES_FOR_CHAIN)
            val effectiveTags = if (isChain) inherited[groupKey].orEmpty() + tags else tags
            val shopLatitude = center.optDouble("lat")
            val shopLongitude = center.optDouble("lon")
            val category = tags["shop"]?.takeIf(String::isNotBlank) ?: "marketplace"
            val sustainability = StoreSustainability.evaluate(raw.name, category, effectiveTags, isChain = isChain)
            NearbyStore(
                name = raw.name,
                category = category,
                website = StoreWebsiteResolver.resolve(
                    raw.name,
                    (tags["website"] ?: tags["contact:website"] ?: tags["url"] ?: brandWebsites[groupKey])
                        ?.takeIf(String::isNotBlank),
                ),
                latitude = shopLatitude,
                longitude = shopLongitude,
                distanceMeters = distanceMeters(latitude, longitude, shopLatitude, shopLongitude),
                sustainable = sustainability.hasLeaf,
                searchContext = listOf(tags["addr:street"], tags["addr:city"], tags["addr:postcode"], tags["brand"])
                    .filterNot { it.isNullOrBlank() }.joinToString(" "),
                sustainabilityScore = sustainability.score,
                sustainabilityReasons = sustainability.reasons,
                osmType = raw.item.optString("type").takeIf(String::isNotBlank)?.uppercase(Locale.ROOT),
                osmId = raw.item.optLong("id").takeIf { it > 0 },
                brand = brand,
                brandWikidata = tags["brand:wikidata"] ?: brandWikidata[groupKey],
                place = placesOf(raw).joinToString(" ").ifBlank { null },
            )
        }.let(StoreDeduplicator::merge)
    }

    private fun scanShop(shop: NearbyStore, userLat: Double, userLon: Double, deep: Boolean = true): List<Offer> {
        val website = shop.website?.let(HttpFetcher::secureUrl) ?: return emptyList()
        val homepage = http.politeGet(website)?.toString(Charsets.UTF_8) ?: return emptyList()
        val links = candidateLinks(shop.name, website, homepage).take(MAX_DOCUMENTS_PER_STORE)
        val distance = distanceMeters(userLat, userLon, shop.latitude, shop.longitude)
        val result = parseHtmlProducts(homepage, website, shop, distance, promotional = false).toMutableList()
        val productDetailLinks = HtmlProductParser.detailLinks(homepage, website).toMutableSet()
        links.forEachIndexed { index, link ->
            val isPdf = link.substringBefore('?').endsWith(".pdf", true)
            if (isPdf && !deep) return@forEachIndexed
            val bytes = http.politeGet(link, if (isPdf) HttpFetcher.MAX_PDF_BYTES else HttpFetcher.MAX_HTML_BYTES)
                ?: return@forEachIndexed
            if (!deep && bytes.startsWithPdfHeader()) return@forEachIndexed
            result += if (isPdf || bytes.startsWithPdfHeader()) {
                parsePdf(bytes, shop, distance, index)
            } else {
                val html = bytes.toString(Charsets.UTF_8)
                productDetailLinks += HtmlProductParser.detailLinks(html, link)
                parseHtmlProducts(html, link, shop, distance, promotional = PROMOTION_PATH_HINTS.any { link.contains(it, true) })
            }
        }
        // Small shops' sites (WooCommerce without Store API, PrestaShop, Magento, Wix…)
        // list their product pages in the sitemap: each page carries name and price.
        // Always, for the shop's own site: flyers only give offers, the catalogue gives the
        // ordinary shelf prices shown when a product is not on offer.
        if (deep && AGGREGATOR_HOSTS.none { hostOf(website).contains(it) }) productDetailLinks += sitemapProductUrls(website)
        productDetailLinks.take(if (deep) MAX_PRODUCT_DETAIL_PAGES else MAX_FAST_DETAIL_PAGES).forEach { detailUrl ->
            val html = http.politeGet(detailUrl)?.toString(Charsets.UTF_8) ?: return@forEach
            result += parseHtmlProducts(html, detailUrl, shop, distance, promotional = false)
        }
        return result.distinctBy { Triple(it.productName.lowercase(Locale.ROOT), it.price, it.productImageUrl) }
    }

    private fun sitemapProductUrls(website: String): List<String> {
        val host = hostOf(website)
        val urls = mutableListOf<String>()
        val queue = ArrayDeque(http.sitemapsFor(website))
        var fetched = 0
        while (queue.isNotEmpty() && fetched < MAX_SITEMAP_FILES && urls.size < MAX_PRODUCT_DETAIL_PAGES * 3) {
            val sitemap = queue.removeFirst()
            val xml = http.politeGet(sitemap, HttpFetcher.MAX_HTML_BYTES)?.toString(Charsets.UTF_8) ?: continue
            fetched++
            val locations = SITEMAP_LOC.findAll(xml).map { it.groupValues[1].trim().replace("&amp;", "&") }.toList()
            if (xml.contains("<sitemapindex", ignoreCase = true)) {
                // Follow product sitemaps first.
                locations.sortedByDescending { loc -> PRODUCT_URL_HINTS.count { loc.contains(it, true) } }
                    .filter { loc -> PRODUCT_URL_HINTS.any { loc.contains(it, true) } }
                    .take(3).forEach(queue::addLast)
            } else {
                urls += locations.filter { hostOf(it) == host }
            }
        }
        return urls.sortedByDescending { url -> PRODUCT_URL_HINTS.count { url.contains(it, true) } }.distinct()
    }

    private fun candidateLinks(storeName: String, base: String, html: String): List<String> =
        runCatching { ChainSourceAdapters.forStore(storeName, base).candidateLinks(base, html) }.getOrDefault(emptyList())

    /** Finds public catalogue/offer pages when OpenStreetMap has no website or the main site has no parsable offers. */
    private fun discoverPublicSources(storeName: String, searchContext: String = ""): List<String> {
        val queries = listOf(
            "\"$storeName\" $searchContext prezzi listino prodotti",
            "\"$storeName\" $searchContext offerte volantino",
            "\"$storeName\" $searchContext spesa online",
        )
        val candidates = queries.flatMap { queryText ->
            val query = URLEncoder.encode(queryText, Charsets.UTF_8.name())
            http.getText("https://html.duckduckgo.com/html/?q=$query")?.let(::searchResultUrls).orEmpty()
        }
        return candidates.filterNot { url ->
            val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            host.contains("duckduckgo.com") || host.contains("facebook.com") || host.contains("instagram.com") ||
                host.contains("tripadvisor") || host.contains("google.")
        }.distinct().filter { url ->
            // Chains may be recognised from the address alone; independent shops only
            // from a page that names them AND their street/town.
            val chain = StoreDeduplicator.brandOf(storeName) != null
            (chain && CatalogSanitizer.sourceReferencesStore(url, storeName)) ||
                (searchContext.isNotBlank() && verifiedStorePage(url, storeName, searchContext))
        }.take(MAX_DISCOVERED_SOURCES)
    }

    private fun verifiedStorePage(url: String, storeName: String, searchContext: String): Boolean {
        val bytes = http.politeGet(url, MAX_VERIFICATION_BYTES) ?: return false
        val text = runCatching { org.jsoup.Jsoup.parse(bytes.toString(Charsets.UTF_8), url).text() }.getOrDefault("")
        return CatalogSanitizer.pageReferencesStore(text, storeName, searchContext)
    }

    /** Applies what is already known about the store's brand (no network). */
    private fun withBrandInfo(store: NearbyStore, now: Long): NearbyStore {
        val info = (store.brand ?: StoreDeduplicator.brandOf(store.name))?.let(brands::cached) ?: return store
        val withSite = store.copy(website = store.website ?: info.website)
        return info.description?.let { applyBrandDescription(withSite, it, now) } ?: withSite
    }

    /** Adds brand-level evidence (e.g. Wikidata "catena di supermercati biologici") to the store leaf. */
    private fun applyBrandDescription(shop: NearbyStore, description: String, now: Long): NearbyStore {
        val extra = StoreSustainability.evaluate("", "", emptyMap(), isChain = true, brandDescription = description)
        val newReasons = extra.reasons.filterNot(shop.sustainabilityReasons::contains)
        if (newReasons.isEmpty()) return shop
        val score = (shop.sustainabilityScore + extra.score).coerceAtMost(100)
        return shop.copy(
            sustainabilityScore = score,
            sustainabilityReasons = shop.sustainabilityReasons + newReasons,
            sustainable = score >= StoreSustainabilityResult.LEAF_THRESHOLD,
        )
    }

    private fun parseStructuredProducts(html: String, shop: NearbyStore, distance: Int, promotional: Boolean): List<Offer> {
        val result = mutableListOf<Offer>()
        JSON_LD.findAll(html).forEach { match ->
            val payload = runCatching { JSONTokener(match.groupValues[1].trim()).nextValue() }.getOrNull() ?: return@forEach
            walkJson(payload).forEach { product ->
                val name = org.jsoup.Jsoup.parse(product.optString("name")).text().trim()
                val offer = firstOffer(product.opt("offers"))
                val price = offer?.let(::jsonLdPrice)
                val image = product.opt("image")
                val imageUrl = (
                    image as? String
                        ?: if (image is JSONArray) image.optString(0) else (image as? JSONObject)?.optString("url")
                    )?.takeIf { it.startsWith("http") }
                if (name.isNotBlank() && price != null && price > 0.0) {
                    result += offer(
                        name, shop.name, price, distance,
                        productImageUrl = imageUrl,
                        storeWebsite = shop.website,
                        promotional = promotional || offer.optString("priceValidUntil").isNotBlank(),
                    )
                }
            }
        }
        return result
    }

    private fun firstOffer(value: Any?): JSONObject? = when (value) {
        is JSONObject -> if (value.has("offers")) firstOffer(value.opt("offers")) ?: value else value
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { firstOffer(value.opt(it)) }
        else -> null
    }

    private fun jsonLdPrice(offer: JSONObject): Double? {
        val currency = offer.optString("priceCurrency")
        if (currency.isNotBlank() && !currency.equals("EUR", true)) return null
        val raw = sequenceOf("price", "lowPrice").map { offer.opt(it)?.toString() }.firstOrNull { !it.isNullOrBlank() }
            ?: (offer.opt("priceSpecification") as? JSONObject)?.opt("price")?.toString()
            ?: ((offer.opt("priceSpecification") as? JSONArray)?.optJSONObject(0))?.opt("price")?.toString()
        return raw?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.01..10_000.0 }
    }

    private fun parseHtmlProducts(html: String, pageUrl: String, shop: NearbyStore, distance: Int, promotional: Boolean): List<Offer> {
        val structured = runCatching { parseStructuredProducts(html, shop, distance, promotional) }.getOrDefault(emptyList())
        val cards = runCatching { HtmlProductParser.parse(html, pageUrl) }.getOrDefault(emptyList()).map { product ->
            offer(
                name = product.name,
                store = shop.name,
                price = product.price,
                distance = distance,
                productImageUrl = product.imageUrl?.takeIf { it.startsWith("http") },
                storeWebsite = shop.website,
                promotional = promotional,
                productImageVerified = product.imageUrl != null,
            )
        }
        // DOM cards keep name, price and image in the same container, so prefer them.
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

    /**
     * PDFs are spooled to a temporary file and parsed with bounded memory; only
     * one PDF/OCR job runs at a time in the whole process to avoid OOM kills.
     */
    private fun parsePdf(bytes: ByteArray, shop: NearbyStore, distance: Int, documentIndex: Int): List<Offer> = synchronized(PDF_LOCK) {
        val temp = runCatching { java.io.File.createTempFile("flyer", ".pdf", appContext.cacheDir) }.getOrNull()
            ?: return emptyList()
        try {
            temp.writeBytes(bytes)
            PDDocument.load(temp, MemoryUsageSetting.setupMixed(PDF_MEMORY_BYTES)).use { document ->
                val ocrOffers = runCatching {
                    OcrFlyerReader.readOffers(
                        document = document,
                        outputDirectory = appContext.filesDir.resolve("flyer_product_images"),
                        key = "${shop.id}-$documentIndex",
                    )
                }.getOrDefault(emptyList()).mapIndexed { index, parsed ->
                    offer(
                        name = parsed.productName, store = shop.name, price = parsed.price, distance = distance,
                        salt = documentIndex * 1_000 + index, productImageUrl = parsed.imagePath,
                        storeWebsite = shop.website, promotional = true, productImageVerified = true,
                    )
                }
                if (ocrOffers.isNotEmpty()) return@use ocrOffers
                val embedded = PDFTextStripper().apply { endPage = MAX_PDF_TEXT_PAGES }.getText(document).lineSequence()
                    .map(String::trim).filter(String::isNotBlank).toList()
                OfferTextParser.parse(embedded).map { parsed ->
                    offer(parsed.productName, shop.name, parsed.price, distance, documentIndex, storeWebsite = shop.website)
                }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            temp.delete()
        }
    }

    private fun SourceProduct.toOffer(shop: NearbyStore): Offer = offer(
        name = name,
        store = shop.name,
        price = price,
        distance = shop.distanceMeters,
        productImageUrl = imageUrl,
        storeWebsite = sourceUrl,
        promotional = promotional,
        productImageVerified = imageUrl != null,
    ).copy(sustainabilityLabels = (labels + inferredSustainabilityLabels(name)).distinct())

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
        distanceMeters = distance, qualityScore = null, sustainabilityLabels = inferredSustainabilityLabels(name),
        validUntil = null, imageKey = imageFor(name), productImageUrl = productImageUrl, storeWebsite = storeWebsite,
        promotional = promotional,
        productImageVerified = productImageVerified,
    )

    private fun readCache(): List<Offer> = runCatching {
        val array = JSONArray(catalogFile.readText())
        List(array.length()) { index -> array.getJSONObject(index).toLegacyOffer() }
            .filter { OfferTextParser.looksLikeProductName(it.productName) }
    }.getOrDefault(emptyList())

    private fun JSONObject.toLegacyOffer(): Offer {
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
        if (!catalogFile.exists()) return
        if (dao.activeOffers(System.currentTimeMillis()).isEmpty()) {
            val now = System.currentTimeMillis()
            val entities = readCache().map { offer ->
                val store = NearbyStore(
                    id = StoreDeduplicator.stableId(offer.storeName, 0.0, 0.0),
                    name = offer.storeName, category = "legacy", website = offer.storeWebsite,
                    latitude = 0.0, longitude = 0.0, distanceMeters = offer.distanceMeters, sustainable = false,
                )
                offer.toEntity(store, offer.storeWebsite ?: "legacy-cache", now)
            }
            if (entities.isNotEmpty()) dao.upsertOffers(entities)
        }
        catalogFile.delete()
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
        sustainabilityScore = sustainabilityScore,
        sustainabilityReasons = sustainabilityReasons.joinToString("|").ifBlank { null },
        osmType = osmType,
        osmId = osmId,
        brand = brand,
        brandWikidata = brandWikidata,
        place = place,
        reviewRating = reviewRating,
        reviewCount = reviewCount,
        priceLevel = priceLevel,
    )

    private fun StoreEntity.toNearbyStore() = NearbyStore(
        id = id,
        name = name,
        category = category,
        website = NonShopSites.shopWebsiteOrNull(website),
        latitude = latitude,
        longitude = longitude,
        distanceMeters = distanceMeters,
        sustainable = sustainabilityScore >= StoreSustainabilityResult.LEAF_THRESHOLD,
        sustainabilityScore = sustainabilityScore,
        sustainabilityReasons = sustainabilityReasons?.split('|')?.filter(String::isNotBlank).orEmpty(),
        osmType = osmType,
        osmId = osmId,
        brand = brand,
        brandWikidata = brandWikidata,
        place = place,
        reviewRating = reviewRating,
        reviewCount = reviewCount,
        priceLevel = priceLevel,
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
        val parser = when {
            sourceUrl.contains("prices.openfoodfacts.org") -> OpenPricesSource.PARSER_ID
            sourceUrl.contains("/wp-json/wc/store") -> EcommerceApiSource.WOO_ID
            sourceUrl.contains("/products.json") || sourceUrl.contains("/products/") -> EcommerceApiSource.SHOPIFY_ID
            else -> ChainSourceAdapters.forStore(store.name, sourceUrl).id
        }
        return OfferEntity(
            id = fingerprint.hashCode().toLong().and(0xffffffffL),
            fingerprint = fingerprint,
            storeId = store.id,
            storeName = store.name,
            productName = productName,
            price = price,
            distanceMeters = store.distanceMeters,
            productImageUrl = productImageUrl?.takeUnless(CatalogSanitizer::isWholeFlyerImage),
            storeWebsite = store.website,
            sourceUrl = sourceUrl,
            parserId = parser,
            confidence = when {
                parser == OpenPricesSource.PARSER_ID -> 0.9
                parser == EcommerceApiSource.WOO_ID || parser == EcommerceApiSource.SHOPIFY_ID -> 0.88
                productImageUrl != null -> 0.92
                else -> 0.72
            },
            observedAt = now,
            expiresAt = now + OFFER_TTL_MILLIS,
            promotional = promotional,
            productImageVerified = productImageVerified,
            labels = sustainabilityLabels.joinToString("|").ifBlank { null },
        )
    }

    private fun OfferEntity.toOffer(store: StoreEntity?) = Offer(
        id = id,
        productName = productName,
        brand = null,
        storeName = store?.name ?: storeName,
        price = price,
        unitPrice = null,
        distanceMeters = store?.distanceMeters ?: distanceMeters,
        // No retailer publishes reviews: leave it empty so the Quality sort uses the
        // per-product estimate (verified photo, brand, labels…) instead of one value
        // shared by every product of the same source, which made the sort look inert.
        // Only a price the user reported carries the quality they gave it.
        qualityScore = userQuality?.toFloat(),
        sustainabilityLabels = (labels?.split('|').orEmpty().filter(String::isNotBlank) +
            inferredSustainabilityLabels(productName)).distinct(),
        validUntil = null,
        imageKey = imageFor(productName),
        productImageUrl = productImageUrl?.takeUnless(CatalogSanitizer::isWholeFlyerImage),
        storeWebsite = storeWebsite,
        promotional = promotional,
        productImageVerified = productImageVerified,
        storeSustainabilityScore = store?.sustainabilityScore ?: 0,
        storeSustainabilityReasons = store?.sustainabilityReasons?.split('|')?.filter(String::isNotBlank).orEmpty(),
    )

    private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url)

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

    private fun inferredSustainabilityLabels(name: String): List<String> = buildList {
        val normalized = name.lowercase(Locale.ROOT)
        if (Regex("\\b(bio|biologico|biologica|organic)\\b").containsMatchIn(normalized)) add("Biologico")
        if (listOf("fairtrade", "fair trade", "equo", "equosolidale").any(normalized::contains)) add("Equosolidale")
        if (listOf("km 0", "km0", "filiera corta", "locale").any(normalized::contains)) add("Filiera locale")
        if (listOf("allevato all'aperto", "allevate a terra", "cruelty free", "benessere animale").any(normalized::contains)) {
            add("Benessere animale")
        }
    }.distinct()

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return (6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))).roundToInt()
    }

    private fun ByteArray.startsWithPdfHeader() = size >= 4 && copyOfRange(0, 4).toString(Charsets.US_ASCII) == "%PDF"

    /** SHA-1 of the signing certificate, uppercase hex (format expected by X-Android-Cert). */
    private fun signingCertSha1(context: Context): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
        java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    private companion object {
        val PDF_LOCK = Any()
        val OVERPASS_URLS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) ShopEasily/0.5 (+https://github.com/StitchMl/ShopEasily)"
        const val PARALLEL_STORES = 4
        const val PARALLEL_FAST = 8
        const val MAX_FAST_DETAIL_PAGES = 4
        const val STORE_LIST_TTL_MS = 7L * 24 * 60 * 60 * 1_000
        const val MIN_BRANCHES_FOR_CHAIN = 2
        val AGGREGATOR_HOSTS = listOf("doveconviene", "volantinofacile", "promoqui", "kimbino", "tiendeo", "openfoodfacts", "facebook", "instagram")
        const val MIN_PRODUCTS_BEFORE_SITEMAP = 5
        const val MAX_SITEMAP_FILES = 4
        val SITEMAP_LOC = Regex("""<loc>\s*([^<\s]+)\s*</loc>""", RegexOption.IGNORE_CASE)
        val PRODUCT_URL_HINTS = listOf("product", "prodott", "/shop/", "/negozio/", "/p/", "articol", "catalog")
        const val MAX_SYNC_STORES = 90
        const val MAX_REPORT_STORES = 80
        const val USER_PARSER_ID = "user"
        /** A price seen on the shelf stays useful for about two months. */
        const val USER_PRICE_TTL_MS = 60L * 24 * 60 * 60 * 1_000
        const val MAX_OVERPASS_BYTES = 16 * 1024 * 1024
        const val GOOGLE_REFRESH_MS = 3L * 24 * 60 * 60 * 1_000
        const val MAX_TARGETED_IDENTITY_LOOKUPS = 12
        const val MAX_OFFERS_PER_STORE = 400
        const val MAX_DOCUMENTS_PER_STORE = 6
        const val MAX_SOURCE_PAGES_PER_STORE = 3
        const val MAX_DISCOVERED_SOURCES = 3
        const val MAX_QUERY_STORES = 12
        const val MAX_QUERY_PAGES = 3
        const val MAX_PRODUCT_DETAIL_PAGES = 30
        const val MAX_PDF_TEXT_PAGES = 30
        const val MAX_VERIFICATION_BYTES = 2 * 1024 * 1024
        const val PDF_MEMORY_BYTES = 8L * 1024 * 1024
        const val OFFER_TTL_MILLIS = 14L * 24 * 60 * 60 * 1_000
        const val REFRESH_UPDATED_AFTER_MS = 12L * 60 * 60 * 1_000
        const val RETRY_FAILED_AFTER_MS = 6L * 60 * 60 * 1_000
        val JSON_LD = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val SEARCH_RESULT_LINK = Regex("""href=["']([^"']*(?:uddg=|https?%3A%2F%2F)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val PROMOTION_PATH_HINTS = setOf("offert", "promo", "volantin", "scont")
    }
}
