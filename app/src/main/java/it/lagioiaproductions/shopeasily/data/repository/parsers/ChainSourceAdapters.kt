package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.net.URI

interface ChainSourceAdapter {
    val id: String
    fun candidateLinks(base: String, html: String): List<String>
}

/**
 * Finds flyer/offer/catalogue links on any retailer homepage. It is the same
 * for every shop: no per-chain rules to maintain.
 */
object GenericSourceAdapter : ChainSourceAdapter {
    override val id: String = "generic"
    private val preferredWords = setOf("volantin", "offert", "promozion", "promo", "catalog", "listino", "prodott", "shop", "negozio-online")
    private val HREF = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)

    override fun candidateLinks(base: String, html: String): List<String> {
        val origin = URI(base)
        return HREF.findAll(html).mapNotNull { match ->
            runCatching { origin.resolve(match.groupValues[1].trim()).toString() }.getOrNull()
        }.filter { url ->
            url.startsWith("http") && (
                url.substringBefore('?').endsWith(".pdf", true) || preferredWords.any { url.contains(it, true) }
                )
        }.distinct().sortedByDescending { url -> preferredWords.count { url.contains(it, true) } }.toList()
    }
}

object ChainSourceAdapters {
    fun forStore(@Suppress("UNUSED_PARAMETER") storeName: String, @Suppress("UNUSED_PARAMETER") website: String): ChainSourceAdapter =
        GenericSourceAdapter
}
