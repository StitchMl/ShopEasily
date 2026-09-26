package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.text.Normalizer
import java.util.Locale

object CatalogSanitizer {
    fun isPlausible(name: String, price: Double, storeName: String, sourceUrl: String, imageUrl: String?): Boolean {
        if (price < MINIMUM_PLAUSIBLE_PRICE || !OfferTextParser.looksLikeProductName(name)) return false
        val evidence = normalize("$sourceUrl ${imageUrl.orEmpty()}")
        if (aggregatorHosts.any(evidence::contains) && !sourceReferencesStore(evidence, storeName)) return false
        val detectedChain = chainAliases.entries.firstOrNull { (_, aliases) -> aliases.any(evidence::contains) }?.key
        return detectedChain == null || chainAliases.getValue(detectedChain).any(normalize(storeName)::contains)
    }

    fun isWholeFlyerImage(url: String?): Boolean {
        val value = normalize(url.orEmpty())
        return listOf("gibcover", "/pages/", "page_0", "flyer-cover", "catalog-cover").any(value::contains)
    }

    fun sourceReferencesStore(url: String, storeName: String): Boolean {
        val normalizedUrl = normalize(url)
        val storeTokens = normalize(storeName).split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in genericStoreWords }
        return storeTokens.any(normalizedUrl::contains)
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
    private val chainAliases = mapOf(
        "esselunga" to setOf("esselunga"),
        "todis" to setOf("todis"),
        "eurospin" to setOf("eurospin"),
        "conad" to setOf("conad"),
        "coop" to setOf("coop"),
        "carrefour" to setOf("carrefour"),
        "lidl" to setOf("lidl"),
        "penny" to setOf("penny"),
        "aldi" to setOf("aldi"),
        "deco" to setOf("deco"),
        "tigota" to setOf("tigota"),
        "natura-si" to setOf("naturasi", "natura-si", "natura si"),
        "si-con-te" to setOf("si-con-te", "siconte", "si con te"),
        "eataly" to setOf("eataly"),
        "divina-carni" to setOf("divinacarni", "divina carni"),
    )
}
