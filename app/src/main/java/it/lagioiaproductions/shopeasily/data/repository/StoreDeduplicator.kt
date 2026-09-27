package it.lagioiaproductions.shopeasily.data.repository

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** A point of sale and the retail brand it belongs to (from the OSM `brand` tag or repeated names). */
data class BrandRecord(val storeName: String, val brand: String)

/**
 * Groups branches and store formats under their retail brand.
 *
 * There is no hand-written list of chains: brands are learnt at runtime from
 * OpenStreetMap (`brand` tag, or the same name used by several shops) through
 * [registerBrands]. "Carrefour Market Roma" belongs to "Carrefour" because
 * every token of the registered brand appears in the store name.
 */
object StoreDeduplicator {
    @Volatile private var brandByStore: Map<String, String> = emptyMap()
    @Volatile private var brandTokens: List<Pair<String, List<String>>> = emptyList()
    @Volatile private var displayNames: Map<String, String> = emptyMap()
    @Volatile private var partialBrands: Set<String> = emptySet()

    fun canonicalName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** Adds brands discovered from OpenStreetMap; safe to call repeatedly from any thread. */
    @Synchronized
    fun registerBrands(records: Collection<BrandRecord>, overwrite: Boolean = true) {
        if (records.isEmpty()) return
        val byStore = brandByStore.toMutableMap()
        val names = displayNames.toMutableMap()
        val partial = partialBrands.toMutableSet()
        records.forEach { record ->
            val brand = canonicalName(record.brand)
            if (brand.isBlank() || isGenericName(record.brand)) return@forEach
            val store = canonicalName(record.storeName)
            if (overwrite || store !in byStore) byStore[store] = brand
            if (overwrite) names[brand] = record.brand.trim() else names.putIfAbsent(brand, record.brand.trim())
            // Brands declared in OpenStreetMap also match other names containing them
            // ("Carrefour Market Roma"); brands only guessed from names match exactly.
            if (overwrite) partial += brand
        }
        if (byStore == brandByStore && names == displayNames && partial == partialBrands) return
        brandByStore = byStore
        displayNames = names
        partialBrands = partial
        keyCache.clear()
        brandTokens = partial.map { it to it.split(' ') }.sortedByDescending { it.second.size }
        recomputeFamilies(partial)
    }

    private val genericWords = setOf(
        "alimentari", "alimentare", "supermercato", "supermercati", "minimarket", "market", "mini", "frutta", "verdura",
        "ortofrutta", "frutteria", "macelleria", "panificio", "panetteria", "forno", "mercato", "rionale", "coperto",
        "salumeria", "pescheria", "drogheria", "latteria", "pasticceria", "gastronomia", "bio", "e", "di", "del", "la", "il",
        "market", "food", "shop", "store", "discount", "emporio", "bottega", "negozio", "spesa",
        // Store-format prefixes: "Iper Triscount" is Triscount, not a brand called "Iper".
        "iper", "ipermercato", "super", "mini", "maxi", "mega", "extra", "hyper", "supermarket", "express",
    )

    /** "Alimentari", "Frutta e verdura", "Mercato rionale": descriptive names, not brands. */
    fun isGenericName(value: String): Boolean {
        val tokens = canonicalName(value).split(' ').filter(String::isNotBlank)
        if (tokens.isEmpty()) return true
        // A short name on its own is a brand ("MD", "A&O"); short words next to others are fillers.
        if (tokens.size == 1) return tokens.single() in genericWords
        return tokens.all { it in genericWords || it.length <= 2 }
    }

    /** Tokens that can identify a business: no generic shop words ("supermercato", "alimentari"…). */
    fun meaningfulTokens(value: String): List<String> =
        canonicalName(value).split(' ').filter { it.length >= 2 && it !in genericWords }

    /**
     * Learns brands from the names of the shops around the user, with no list to maintain:
     * a word is a brand when it opens at least two different shop names
     * ("Carrefour Market", "Carrefour Express") or when it is itself the name of a
     * shop that also appears inside other names ("Conad", "Spazio Conad", "Conad City").
     */
    fun learnFromNames(names: Collection<String>, places: Collection<String> = emptyList()) {
        // Place names from the shops' addresses (city, district, street) are never brands: "Eataly Roma".
        val placeWords = places.flatMap { canonicalName(it).split(' ') }.filter { it.length >= 3 }.toSet()
        // The same distinctive name used by several shops ("Oasi", "Oasi") is a chain.
        val repeated = names.map(String::trim)
            .filter { name -> !isGenericName(name) && meaningfulTokens(name).any { it !in placeWords } }
            .groupBy(::canonicalName).filterValues { it.size >= 2 }.values.map { it.first() }
        if (repeated.isNotEmpty()) registerBrands(repeated.map { BrandRecord(it, it) }, overwrite = false)
        val distinct = names.map(String::trim).filter(String::isNotBlank).distinctBy(::canonicalName)
        if (distinct.size < 2) return
        val tokenized = distinct.associateWith { name -> meaningfulTokens(name).filterNot(placeWords::contains) }
            .filterValues { it.isNotEmpty() }
        val firstCounts = tokenized.values.groupingBy { it.first() }.eachCount()
        val totalCounts = tokenized.values.flatMap { it.distinct() }.groupingBy { it }.eachCount()
        val singleNames = tokenized.filterValues { it.size == 1 }.entries
            .groupBy({ it.value.single() }, { it.key.trim() })
            .mapValues { (token, candidates) -> candidates.firstOrNull { canonicalName(it) == token } ?: token.replaceFirstChar { it.titlecase(Locale.ITALY) } }
        val brandWords = totalCounts.filter { (token, total) ->
            (firstCounts[token] ?: 0) >= 2 || (token in singleNames && total >= 2)
        }
        if (brandWords.isEmpty()) return
        val records = tokenized.mapNotNull { (name, tokens) ->
            // A brand normally opens the name ("Eataly Roma", "Conad City"); an inner word counts
            // only if it opens other names too ("Spazio Conad", with "Conad" and "Conad City").
            val first = tokens.first()
            val token = first.takeIf(brandWords::containsKey)
                ?: tokens.drop(1).filter { (firstCounts[it] ?: 0) >= 2 }.maxByOrNull { brandWords.getValue(it) }
                ?: return@mapNotNull null
            val display = singleNames[token] ?: token.replaceFirstChar { it.titlecase(Locale.ITALY) }
            BrandRecord(name, display)
        }
        registerBrands(records, overwrite = false)
    }

    /** Brand of a shop if one is known (from OSM or learnt from names), otherwise null. */
    fun brandOf(storeName: String): String? {
        val key = brandKey(storeName)
        return if (key in displayNames || key in families) brandDisplayName(storeName) else null
    }

    /**
     * Groups formats and branches under the retail brand used by UI filters.
     * Store formats are folded into their family automatically: "Conad City",
     * "Spazio Conad" and "Conad Superstore" → "conad"; "Carrefour Market" and
     * "Carrefour Express" → "carrefour"; "Ipercoop" → "coop".
     */
    fun brandKey(value: String): String = keyCache.getOrPut(value) { computeBrandKey(value) }

    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var siteGroupOf: Map<String, String> = emptyMap()

    /**
     * Brands that share the same official website are one retailer for the user:
     * "Oasi" and "Tigre" both live on oasitigre.it → one filter "Oasi Tigre".
     * [hostByBrand] maps a brand key to its site host (e.g. "tigre" → "www.oasitigre.it").
     */
    @Synchronized
    fun registerSiteGroups(hostByBrand: Map<String, String>) {
        val groups = hostByBrand
            .mapValues { (_, host) -> host.lowercase(Locale.ROOT).removePrefix("www.").substringBeforeLast('.').replace(Regex("[^a-z0-9]"), "") }
            .filterValues { it.length >= 3 && socialHosts.none(it::contains) }
            .entries.groupBy({ it.value }, { it.key })
            .filterValues { it.distinct().size >= 2 }
        val mapping = HashMap<String, String>()
        val names = displayNames.toMutableMap()
        groups.forEach { (host, brands) ->
            val ordered = brands.distinct().filter { host.contains(it.replace(" ", "")) }
                .sortedBy { host.indexOf(it.replace(" ", "")) }
            val display = if (ordered.size >= 2) {
                ordered.joinToString(" ") { key -> names[key] ?: key.replaceFirstChar { it.titlecase(Locale.ITALY) } }
            } else {
                brands.distinct().minByOrNull(String::length)?.let { names[it] ?: it } ?: return@forEach
            }
            val groupKey = canonicalName(display)
            names[groupKey] = display
            brands.forEach { mapping[it] = groupKey }
        }
        if (mapping == siteGroupOf && names == displayNames) return
        siteGroupOf = mapping
        displayNames = names
        keyCache.clear()
    }

    private val socialHosts = listOf("facebook", "instagram", "google", "tripadvisor", "paginegialle", "wix", "linktree")

    private fun computeBrandKey(value: String): String {
        val root = computeRootKey(value)
        return siteGroupOf[root] ?: root
    }

    /** Brand of a store before site grouping (e.g. "tigre", not "oasi tigre"). */
    fun rootKey(value: String): String = computeRootKey(value)

    fun rootDisplayName(value: String): String = rootKey(value).let { key ->
        displayNames[key] ?: key.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ITALY) } }
    }

    private fun computeRootKey(value: String): String {
        val canonical = canonicalName(value)
        val direct = brandByStore[canonical] ?: run {
            val tokens = canonical.split(' ').toSet()
            brandTokens.firstOrNull { (_, brand) -> tokens.containsAll(brand) }?.first ?: canonical
        }
        return rootOf(direct)
    }

    @Volatile private var families: Set<String> = emptySet()

    /** Shortest known brand/family contained in [key], applied until stable. */
    private fun rootOf(key: String): String {
        var current = key
        repeat(3) {
            val tokens = current.split(' ')
            val expanded = tokens.flatMap { token -> splitCompound(token) }.toSet()
            val candidates = (partialBrands + families).filter { candidate ->
                candidate != current && !isGenericName(candidate) &&
                    candidate.split(' ').let { expanded.containsAll(it) && it.size <= tokens.size }
            }
            val best = candidates.minWithOrNull(compareBy<String> { it.split(' ').size }.thenBy { it.length }) ?: return current
            if (best == current) return current
            current = best
        }
        return current
    }

    /** "ipercoop" → [ipercoop, coop]: format prefixes glued to a known brand. */
    private fun splitCompound(token: String): List<String> {
        val prefix = formatPrefixes.firstOrNull { token.startsWith(it) && token.length > it.length + 2 } ?: return listOf(token)
        val rest = token.removePrefix(prefix)
        return if (rest in partialBrands || rest in families) listOf(token, rest) else listOf(token)
    }

    private val formatPrefixes = listOf("iper", "super", "mini", "maxi", "mega", "extra")

    /** Words that open at least two different registered brands ("carrefour market", "carrefour express"). */
    private fun recomputeFamilies(brands: Collection<String>) {
        families = brands.mapNotNull { it.split(' ').firstOrNull() }
            .filter { it.length >= 3 && it !in genericWords }
            .groupingBy { it }.eachCount()
            .filter { (_, count) -> count >= 2 }
            .keys
    }

    fun brandDisplayName(value: String): String {
        val key = brandKey(value)
        return displayNames[key]?.takeIf { canonicalName(it) == key }
            ?: key.takeIf { it != canonicalName(value) || it in families }?.split(' ')
                ?.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ITALY) } }
            ?: value.trim()
    }

    fun belongsToBrand(storeName: String, selectedBrand: String): Boolean =
        brandKey(storeName) == brandKey(selectedBrand)

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
                sustainabilityScore = matches.maxOf(NearbyStore::sustainabilityScore),
                sustainabilityReasons = matches.flatMap(NearbyStore::sustainabilityReasons).distinct(),
                osmType = best.osmType ?: matches.firstNotNullOfOrNull(NearbyStore::osmType),
                osmId = best.osmId ?: matches.firstNotNullOfOrNull(NearbyStore::osmId),
                brand = best.brand ?: matches.firstNotNullOfOrNull(NearbyStore::brand),
                brandWikidata = best.brandWikidata ?: matches.firstNotNullOfOrNull(NearbyStore::brandWikidata),
                place = best.place ?: matches.firstNotNullOfOrNull(NearbyStore::place),
                distanceMeters = matches.minOf(NearbyStore::distanceMeters),
            )
        }
        .sortedBy(NearbyStore::distanceMeters)

    private fun score(store: NearbyStore): Int =
        (if (!store.website.isNullOrBlank()) 2 else 0) + (if (store.sustainable) 1 else 0) +
            (if (store.osmId != null) 1 else 0)
}
