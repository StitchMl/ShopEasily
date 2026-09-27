package it.lagioiaproductions.shopeasily.data.repository

import android.content.Context
import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import it.lagioiaproductions.shopeasily.data.repository.net.NonShopSites
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** What ShopEasily learnt automatically about a retail brand. */
data class BrandInfo(
    val brand: String,
    val website: String?,
    val logoUrl: String?,
    val flyerUrls: List<String>,
    val description: String?,
    val wikidataId: String?,
    val resolvedAt: Long,
)

/**
 * Discovers official website, logo and public flyer pages of a retail brand
 * without any hand-written list:
 *
 * 1. the OpenStreetMap `brand:wikidata` tag, or a Wikidata search by brand name
 *    filtered on retail descriptions → official website (P856) and logo (P154);
 * 2. otherwise a web search, accepting only a domain that contains the brand name;
 * 3. flyer pages: links on the official site plus flyer aggregators whose
 *    address names the brand (found by web search and verified).
 *
 * Results are cached on the device for 30 days (3 days when nothing is found).
 */
class BrandDirectory private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("brand_directory_v5", Context.MODE_PRIVATE)
    private val http = HttpFetcher(USER_AGENT)
    private val memory = ConcurrentHashMap<String, BrandInfo>()
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        preferences.all.forEach { (key, value) ->
            (value as? String)?.let(::decode)?.let { memory[key] = it }
        }
    }

    fun cached(brand: String): BrandInfo? = memory[StoreDeduplicator.canonicalName(brand)]

    /** Blocking network call: use from a background dispatcher. */
    /**
     * [locationHint] (street/city) is given for independent shops: a shop called
     * "Fresco Market" in Rome must not get the site of a namesake in Bari. Their
     * results are cached per name+place, never shared with other shops.
     */
    fun resolve(
        brand: String,
        wikidataId: String?,
        now: Long = System.currentTimeMillis(),
        locationHint: String? = null,
    ): BrandInfo {
        val place = locationHint?.let { locationTokens(it) }.orEmpty()
        val key = StoreDeduplicator.canonicalName(brand) + if (place.isEmpty()) "" else "@" + place.take(2).joinToString(" ")
        fun usable(info: BrandInfo?) = info?.takeIf {
            // An OSM brand:wikidata id beats a result found by name search.
            it.isFresh(now) && (wikidataId == null || it.wikidataId == wikidataId)
        }
        usable(memory[key])?.let { return it }
        return synchronized(locks.getOrPut(key) { Any() }) {
            usable(memory[key]) ?: lookup(brand, wikidataId, now, place).also { info ->
                memory[key] = info
                preferences.edit().putString(key, encode(info)).apply()
            }
        }
    }

    private fun BrandInfo.isFresh(now: Long): Boolean {
        val ttl = if (website == null && flyerUrls.isEmpty()) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        return now - resolvedAt < ttl
    }

    private fun lookup(brand: String, wikidataId: String?, now: Long, place: List<String> = emptyList()): BrandInfo {
        val independent = place.isNotEmpty()
        // Name searches on Wikidata only for chains: an independent "Profumo" is not the namesake item.
        val qid = wikidataId?.takeIf { it.matches(Regex("Q\\d+")) } ?: if (independent) null else searchWikidata(brand)
        var entity = qid?.let(::fetchEntity)
        // Brand items (e.g. "Pam") often lack logo/site, which belong to the parent
        // organisation ("Gruppo PAM"): follow parent organisation / owner, up to 2 hops.
        var hops = 0
        var current = entity
        while (current != null && hops < 2 && (entity?.website == null || entity.logoFile == null)) {
            val parentId = current.parents.firstOrNull() ?: break
            val parent = fetchEntity(parentId) ?: break
            entity = entity?.copy(
                website = entity.website ?: parent.website,
                logoFile = entity.logoFile ?: parent.logoFile,
                description = entity.description ?: parent.description,
            ) ?: parent
            current = parent
            hops++
        }
        val website = NonShopSites.shopWebsiteOrNull(entity?.website)?.let(HttpFetcher::secureUrl)
            ?: guessOfficialSite(brand, place)
            ?: searchOfficialSite(brand)?.takeIf { !independent || pageMentionsPlace(it, place) }
        return BrandInfo(
            brand = brand,
            website = website,
            logoUrl = entity?.logoFile?.let(::commonsThumbnail),
            flyerUrls = if (independent) emptyList() else discoverFlyers(brand),
            description = entity?.description,
            wikidataId = qid,
            resolvedAt = now,
        )
    }

    private fun fetchEntity(id: String): WikidataEntity? =
        http.getText("https://www.wikidata.org/wiki/Special:EntityData/$id.json", HttpFetcher.MAX_JSON_BYTES)
            ?.let { parseWikidataEntity(it, id) }

    /**
     * Name search, then disambiguation on the candidates' data: an Italian retailer
     * (country = Italy) wins over namesakes abroad (French "Simply Market", Swiss "Coop").
     */
    private fun searchWikidata(brand: String): String? {
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=10" +
            "&language=it&uselang=it&search=" + URLEncoder.encode(brandTokens(brand).joinToString(" "), Charsets.UTF_8.name())
        val json = http.getText(url, HttpFetcher.MAX_JSON_BYTES) ?: return null
        val candidates = retailCandidates(json, brand)
        if (candidates.size <= 1) return candidates.firstOrNull()
        val ids = candidates.joinToString("|")
        val details = http.getText(
            "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims|descriptions&ids=$ids",
            HttpFetcher.MAX_JSON_BYTES,
        ) ?: return candidates.first()
        return pickItalianEntity(details, candidates) ?: candidates.first()
    }

    /**
     * Tries the usual domain shapes of Italian retailers built from the brand words
     * ("elite" → elite.it, superelite.it, elitesupermercati.it…) and accepts one only
     * if its home page names the brand and talks about groceries.
     */
    private fun guessOfficialSite(brand: String, place: List<String> = emptyList()): String? {
        val candidates = candidateDomains(brand)
        for (domain in candidates) {
            if (!http.isPublicUrl("https://$domain")) continue
            val html = http.getText("https://$domain", HttpFetcher.MAX_HTML_BYTES) ?: continue
            if (!looksLikeRetailerHome(html, brand)) continue
            // Independent shop: the site must also name its street or town.
            if (place.isNotEmpty() && !textMentionsPlace(html, place)) continue
            return "https://$domain"
        }
        return null
    }

    private fun pageMentionsPlace(url: String, place: List<String>): Boolean {
        val html = http.getText(url, HttpFetcher.MAX_HTML_BYTES) ?: return false
        return textMentionsPlace(html, place)
    }

    private fun searchOfficialSite(brand: String): String? {
        val query = URLEncoder.encode("${brandTokens(brand).joinToString(" ")} $brand sito ufficiale", Charsets.UTF_8.name())
        val html = http.getText("https://html.duckduckgo.com/html/?q=$query") ?: return null
        return pickOfficialSite(searchResultUrls(html), brand)
    }

    private fun discoverFlyers(brand: String): List<String> {
        val query = URLEncoder.encode("${brandTokens(brand).joinToString(" ")} volantino offerte", Charsets.UTF_8.name())
        val results = http.getText("https://html.duckduckgo.com/html/?q=$query")?.let(::searchResultUrls).orEmpty()
        return pickFlyerPages(results, brand).filter { url ->
            val page = http.politeGet(url, 2 * 1024 * 1024)?.toString(Charsets.UTF_8) ?: return@filter false
            val text = StoreDeduplicator.canonicalName(runCatching { org.jsoup.Jsoup.parse(page).title() }.getOrDefault(""))
            brandTokens(brand).all(text::contains)
        }.take(MAX_FLYER_PAGES)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) ShopEasily/0.5 (+https://github.com/StitchMl/ShopEasily)"
        private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        private const val NEGATIVE_TTL_MS = 12L * 60 * 60 * 1_000
        private const val MAX_FLYER_PAGES = 2

        @Volatile private var instance: BrandDirectory? = null

        fun get(context: Context): BrandDirectory = instance ?: synchronized(this) {
            instance ?: BrandDirectory(context).also { instance = it }
        }

        @Volatile private var wikidataByStore: Map<String, String> = emptyMap()

        /** OSM `brand:wikidata` of known stores, so that the UI resolves the exact brand item. */
        fun rememberWikidata(entries: Map<String, String>) {
            if (entries.isEmpty()) return
            val merged = wikidataByStore + entries.mapKeys { StoreDeduplicator.canonicalName(it.key) }
            if (merged != wikidataByStore) wikidataByStore = merged
        }

        /** Logo already discovered for a store's brand, without network (for UI). */
        fun peekLogo(storeName: String): String? {
            val directory = instance ?: return null
            return (directory.memory[StoreDeduplicator.brandKey(storeName)]
                ?: directory.memory[StoreDeduplicator.canonicalName(storeName)])?.logoUrl
        }

        /**
         * Resolves (or reads from cache) the brand of a store by name. Blocking: call from
         * Dispatchers.IO. Returns null for generic names such as "Alimentari".
         */
        fun resolveForStore(storeName: String): BrandInfo? {
            val directory = instance ?: return null
            if (StoreDeduplicator.isGenericName(storeName)) return null
            val wikidata = wikidataByStore[StoreDeduplicator.canonicalName(storeName)]
            // Only chains are looked up by name from the UI; independent shops get their
            // site from the background sync, which checks their address.
            val brand = StoreDeduplicator.brandOf(storeName) ?: storeName.takeIf { wikidata != null } ?: return null
            return runCatching { directory.resolve(brand, wikidata) }.getOrNull()
        }

        fun peekWebsite(storeName: String): String? {
            val directory = instance ?: return null
            return (directory.memory[StoreDeduplicator.brandKey(storeName)]
                ?: directory.memory[StoreDeduplicator.canonicalName(storeName)])?.website
        }

        private val retailWords = listOf(
            "supermercat", "ipermercat", "discount", "catena", "grande distribuzione", "distribuzione organizzata",
            "negozi", "alimentar", "supermarket", "hypermarket", "retail", "grocery", "store chain", "cooperativ",
        )
        private val excludedHosts = listOf(
            "wikipedia.", "wikidata.", "facebook.", "instagram.", "linkedin.", "youtube.", "tiktok.", "twitter.", "x.com",
            "tripadvisor.", "google.", "paginegialle.", "paginebianche.", "virgilio.", "duckduckgo.", "amazon.",
            "doveconviene.", "volantinofacile.", "promoqui.", "kimbino.", "portavolantino.", "tiendeo.", "cercavolantini.",
            "tuttiprezzi.", "shopfully.", "glovo", "deliveroo", "justeat", "ubereats",
        )
        private val flyerHosts = listOf(
            "doveconviene.", "volantinofacile.", "promoqui.", "kimbino.", "portavolantino.", "tiendeo.", "cercavolantini.",
        )

        /** Distinctive words of the brand: "Supermercato Elite" → [elite]. */
        internal fun brandTokens(brand: String): List<String> =
            StoreDeduplicator.meaningfulTokens(brand).ifEmpty { StoreDeduplicator.canonicalName(brand).split(' ').filter { it.length >= 2 } }

        /** Retail items whose label matches the brand, best first. */
        fun retailCandidates(json: String, brand: String): List<String> {
            val results = runCatching { JSONObject(json).optJSONArray("search") }.getOrNull() ?: return emptyList()
            val wanted = brandTokens(brand).joinToString(" ")
            if (wanted.isBlank()) return emptyList()
            return (0 until results.length()).mapNotNull(results::optJSONObject).filter { item ->
                val label = brandTokens(item.optString("label")).joinToString(" ")
                val description = item.optString("description").lowercase(Locale.ROOT)
                (label == wanted || label.startsWith("$wanted ") || wanted.startsWith("$label ")) &&
                    (description.isBlank() || retailWords.any(description::contains))
            }.sortedByDescending { item ->
                val description = item.optString("description").lowercase(Locale.ROOT)
                (if (description.contains("ital")) 2 else 0) + (if (retailWords.any(description::contains)) 1 else 0)
            }.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }.take(6)
        }

        /** Among candidates, the one whose country (P17) is Italy (Q38), or described as Italian. */
        fun pickItalianEntity(json: String, candidates: List<String>): String? {
            val entities = runCatching { JSONObject(json).optJSONObject("entities") }.getOrNull() ?: return null
            return candidates.maxByOrNull { id ->
                val entity = entities.optJSONObject(id) ?: return@maxByOrNull -1
                val countries = itemValues(entity.optJSONObject("claims"), "P17")
                val description = listOf("it", "en").joinToString(" ") { lang ->
                    entity.optJSONObject("descriptions")?.optJSONObject(lang)?.optString("value").orEmpty()
                }.lowercase(Locale.ROOT)
                (if ("Q38" in countries) 4 else 0) + (if (description.contains("ital")) 2 else 0) +
                    (if (retailWords.any(description::contains)) 1 else 0) - candidates.indexOf(id).coerceAtMost(3) / 3
            }
        }

        private fun itemValues(claims: JSONObject?, property: String): List<String> =
            claims?.optJSONArray(property)?.let { values ->
                (0 until values.length()).mapNotNull {
                    (values.optJSONObject(it)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value") as? JSONObject)
                        ?.optString("id")?.takeIf(String::isNotBlank)
                }
            }.orEmpty()

        /** Picks the Wikidata item that is a retailer and whose label matches the brand. */
        fun pickRetailEntity(json: String, brand: String): String? {
            val results = runCatching { JSONObject(json).optJSONArray("search") }.getOrNull() ?: return null
            val wanted = brandTokens(brand).joinToString(" ")
            return (0 until results.length()).mapNotNull(results::optJSONObject).filter { item ->
                val label = brandTokens(item.optString("label")).joinToString(" ")
                val description = item.optString("description").lowercase(Locale.ROOT)
                wanted.isNotBlank() && (label == wanted || label.startsWith("$wanted ") || wanted.startsWith("$label ")) &&
                    retailWords.any(description::contains)
            }.maxByOrNull { item ->
                // Prefer Italian retailers (the Swiss or Swedish "Coop" are different companies).
                val description = item.optString("description").lowercase(Locale.ROOT)
                (if (description.contains("ital")) 2 else 0) + (if (brandTokens(item.optString("label")).joinToString(" ") == wanted) 1 else 0)
            }?.optString("id")?.takeIf(String::isNotBlank)
        }

        data class WikidataEntity(
            val website: String?,
            val logoFile: String?,
            val description: String?,
            /** Parent organisation (P749), owner (P127), part of (P361). */
            val parents: List<String> = emptyList(),
        )

        fun parseWikidataEntity(json: String, id: String): WikidataEntity? {
            val entity = runCatching { JSONObject(json).getJSONObject("entities").getJSONObject(id) }.getOrNull()
                ?: return null
            val claims = entity.optJSONObject("claims")
            fun firstString(property: String): String? = claims?.optJSONArray(property)?.let { values ->
                (0 until values.length()).asSequence()
                    .mapNotNull { values.optJSONObject(it)?.optJSONObject("mainsnak")?.optJSONObject("datavalue") }
                    .mapNotNull { it.opt("value") as? String }
                    .firstOrNull(String::isNotBlank)
            }
            val descriptions = entity.optJSONObject("descriptions")
            val description = listOf("it", "en").firstNotNullOfOrNull { lang ->
                descriptions?.optJSONObject(lang)?.optString("value")?.takeIf(String::isNotBlank)
            }
            // P8972 = small logo/icon (ideal for a 64 px marker), P154 = logo image.
            val parents = listOf("P749", "P127", "P361").flatMap { itemValues(claims, it) }.distinct()
            return WikidataEntity(firstString("P856"), firstString("P8972") ?: firstString("P154"), description, parents)
        }

        fun commonsThumbnail(fileName: String): String =
            "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                URLEncoder.encode(fileName.replace(' ', '_'), Charsets.UTF_8.name()).replace("+", "%20") + "?width=128"

        /** Distinctive words of an address/place ("Via Appia Nuova 21, Roma" → appia, nuova, roma). */
        fun locationTokens(value: String): List<String> = StoreDeduplicator.canonicalName(value).split(' ')
            .filter { it.length >= 4 && it.any(Char::isLetter) && it !in addressWords }
            .distinct()

        private val addressWords = setOf("via", "viale", "piazza", "piazzale", "largo", "corso", "vicolo", "localita", "strada", "italia", "provincia")

        fun textMentionsPlace(html: String, place: List<String>): Boolean {
            if (place.isEmpty()) return true
            val text = " " + StoreDeduplicator.canonicalName(runCatching { org.jsoup.Jsoup.parse(html).text() }.getOrDefault(html)) + " "
            return place.any { text.contains(" $it ") }
        }

        fun candidateDomains(brand: String): List<String> {
            val tokens = brandTokens(brand)
            if (tokens.isEmpty()) return emptyList()
            val compact = tokens.joinToString("")
            val dashed = tokens.joinToString("-")
            if (compact.length < 2) return emptyList()
            val stems = listOf(compact, dashed).distinct()
            return stems.flatMap { stem ->
                listOf(
                    "www.$stem.it", "www.super$stem.it", "www.${stem}supermercati.it", "www.supermercati$stem.it",
                    "www.$stem-supermercati.it", "www.$stem.com", "www.${stem}spa.it", "www.gruppo$stem.it",
                )
            }.distinct()
        }

        fun looksLikeRetailerHome(html: String, brand: String): Boolean {
            val text = StoreDeduplicator.canonicalName(runCatching { org.jsoup.Jsoup.parse(html).text() }.getOrDefault("")).take(200_000)
            val tokens = brandTokens(brand)
            return tokens.isNotEmpty() && tokens.all { " $text ".contains(" $it ") } &&
                listOf("supermercat", "spesa", "offert", "volantin", "punti vendita", "negozi", "prodotti").count(text::contains) >= 2
        }

        /** First search result whose domain contains the brand name (e.g. superelite.it for "Elite"). */
        fun pickOfficialSite(results: List<String>, brand: String): String? {
            val compact = brandTokens(brand).joinToString("")
            val longest = brandTokens(brand).maxByOrNull(String::length) ?: return null
            if (longest.length < 2) return null
            return results.firstNotNullOfOrNull { url ->
                val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (excludedHosts.any(host::contains)) return@firstNotNullOfOrNull null
                val domain = host.removePrefix("www.").substringBeforeLast('.').replace(Regex("[^a-z0-9]"), "")
                if (domain.contains(compact) || domain.contains(longest)) "https://$host" else null
            }
        }

        /** Aggregator pages dedicated to the brand, e.g. doveconviene.it/volantino/elite. */
        fun pickFlyerPages(results: List<String>, brand: String): List<String> {
            val slug = brandTokens(brand).joinToString("-")
            val compact = brandTokens(brand).joinToString("")
            return results.filter { url ->
                val lower = url.lowercase(Locale.ROOT)
                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                flyerHosts.any(host::contains) && (lower.contains("/$slug") || lower.contains("/$compact"))
            }.distinct()
        }

        fun searchResultUrls(html: String): List<String> = SEARCH_RESULT_LINK.findAll(html).mapNotNull { match ->
            val raw = match.groupValues[1].replace("&amp;", "&")
            val encodedTarget = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1)
            (encodedTarget?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() } ?: raw)
                .takeIf { it.startsWith("http") }
        }.distinct().toList()

        private val SEARCH_RESULT_LINK = Regex("""href=["']([^"']*(?:uddg=|https?%3A%2F%2F)[^"']*)["']""", RegexOption.IGNORE_CASE)

        private fun encode(info: BrandInfo): String = JSONObject().apply {
            put("brand", info.brand)
            put("website", info.website ?: JSONObject.NULL)
            put("logo", info.logoUrl ?: JSONObject.NULL)
            put("flyers", JSONArray(info.flyerUrls))
            put("description", info.description ?: JSONObject.NULL)
            put("wikidata", info.wikidataId ?: JSONObject.NULL)
            put("resolvedAt", info.resolvedAt)
        }.toString()

        private fun decode(value: String): BrandInfo? = runCatching {
            val json = JSONObject(value)
            fun JSONObject.nullable(key: String) = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
            val flyers = json.optJSONArray("flyers")
            BrandInfo(
                brand = json.getString("brand"),
                website = json.nullable("website"),
                logoUrl = json.nullable("logo"),
                flyerUrls = if (flyers == null) emptyList() else (0 until flyers.length()).map(flyers::getString),
                description = json.nullable("description"),
                wikidataId = json.nullable("wikidata"),
                resolvedAt = json.getLong("resolvedAt"),
            )
        }.getOrNull()
    }
}
