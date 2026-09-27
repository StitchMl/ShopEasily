package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.text.Normalizer
import java.util.Locale

object CatalogSanitizer {
    /**
     * [knownBrands] are the brands discovered automatically from OpenStreetMap in
     * the user's area (canonical names). If the source or image address names one
     * of them, the offer must belong to a store of that brand: this rejects, for
     * example, a supermarket flyer wrongly attributed to a neighbourhood bakery.
     */
    fun isPlausible(
        name: String,
        price: Double,
        storeName: String,
        sourceUrl: String,
        imageUrl: String?,
        knownBrands: Collection<String> = emptySet(),
    ): Boolean {
        if (price < MINIMUM_PLAUSIBLE_PRICE || !OfferTextParser.looksLikeProductName(name)) return false
        val evidence = normalize("$sourceUrl ${imageUrl.orEmpty()}")
        if (aggregatorHosts.any(evidence::contains) && !sourceReferencesStore(evidence, storeName)) return false
        val evidenceTokens = evidence.split(Regex("[^a-z0-9]+")).filter(String::isNotBlank).toSet()
        val compactEvidence = evidenceTokens.joinToString(" ")
        val store = normalize(storeName).replace(Regex("[^a-z0-9]+"), " ")
        // Only distinctive brand words (4+ letters) count as evidence of another chain.
        val detected = knownBrands.map(::normalize).mapNotNull { brand ->
            brand.split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.takeIf { it.isNotEmpty() }
        }.filter { tokens -> evidenceTokens.containsAll(tokens) }
        if (compactEvidence.isEmpty() || detected.isEmpty()) return true
        val storeTokens = store.split(' ').filter(String::isNotBlank)
        // The store matches if it shares a brand word, also glued ("Ipercoop" ↔ "coop").
        return detected.any { tokens -> tokens.any { token -> storeTokens.any { it == token || it.endsWith(token) || token.endsWith(it) && it.length >= 4 } } }
    }

    fun isWholeFlyerImage(url: String?): Boolean {
        val value = normalize(url.orEmpty())
        return listOf("gibcover", "/pages/", "page_0", "flyer-cover", "catalog-cover").any(value::contains)
    }

    fun sourceReferencesStore(url: String, storeName: String): Boolean {
        val normalizedUrl = normalize(url)
        val urlTokens = normalizedUrl.split(Regex("[^a-z0-9]+")).toSet()
        val storeTokens = normalize(storeName).split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in genericStoreWords }
        // Short brand names (e.g. "Pam") must be a whole token of the address.
        return storeTokens.any { token -> token in urlTokens || (token.length >= 4 && normalizedUrl.contains(token)) }
    }

    /** Verifies search results whose domain is different from the shop name. */
    fun pageReferencesStore(pageText: String, storeName: String, locationHint: String): Boolean {
        val page = normalize(pageText)
        val nameTokens = meaningfulTokens(storeName)
        if (nameTokens.isEmpty()) return false
        val requiredNameMatches = if (nameTokens.size == 1) 1 else 2
        if (nameTokens.count(page::contains) < requiredNameMatches) return false
        val locationTokens = meaningfulTokens(locationHint)
        return locationTokens.isEmpty() || locationTokens.any(page::contains)
    }

    private fun meaningfulTokens(value: String): List<String> = normalize(value)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in genericStoreWords }
        .distinct()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace('_', '-')

    private const val MINIMUM_PLAUSIBLE_PRICE = 0.15
    private val genericStoreWords = setOf(
        "supermercati", "supermercato", "market", "alimentari", "panificio", "forno",
        "carne", "carni", "frutta", "verdura", "macelleria", "frutteria",
    )
    private val aggregatorHosts = setOf("cercavolantini", "doveconviene", "shopfully", "volantinofacile")
}
