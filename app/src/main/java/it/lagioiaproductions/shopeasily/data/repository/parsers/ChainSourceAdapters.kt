package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.net.URI

interface ChainSourceAdapter {
    val id: String
    fun matches(storeName: String, website: String): Boolean
    fun candidateLinks(base: String, html: String): List<String>
}

open class LinkAdapter(
    override val id: String,
    private val names: Set<String>,
    private val preferredWords: Set<String>,
) : ChainSourceAdapter {
    override fun matches(storeName: String, website: String): Boolean =
        names.any { storeName.contains(it, true) || website.contains(it, true) }

    override fun candidateLinks(base: String, html: String): List<String> {
        val origin = URI(base)
        return HREF.findAll(html).mapNotNull { match ->
            runCatching { origin.resolve(match.groupValues[1]).toString() }.getOrNull()
        }.filter { url ->
            url.startsWith("http") && (
                url.substringBefore('?').endsWith(".pdf", true) ||
                    preferredWords.any { url.contains(it, true) }
                )
        }.distinct().sortedByDescending { url -> preferredWords.count { url.contains(it, true) } }.toList()
    }

    companion object {
        private val HREF = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
    }
}

class EsselungaSourceAdapter : LinkAdapter("esselunga", setOf("esselunga"), setOf("volantino", "promozioni", "offerte"))
class CoopSourceAdapter : LinkAdapter("coop", setOf("coop", "incoop"), setOf("volantino", "offerte", "promozioni"))
class ConadSourceAdapter : LinkAdapter("conad", setOf("conad"), setOf("volantino", "offerte", "promo"))
class LidlSourceAdapter : LinkAdapter("lidl", setOf("lidl"), setOf("volantino", "c_", "offerte"))
class EurospinSourceAdapter : LinkAdapter("eurospin", setOf("eurospin"), setOf("volantino", "offerte", "promo"))
class CarrefourSourceAdapter : LinkAdapter("carrefour", setOf("carrefour"), setOf("volantino", "promozioni", "offerte"))
class GenericSourceAdapter : LinkAdapter("generic", emptySet(), setOf("offert", "volantin", "catalog", "promozion", "promo")) {
    override fun matches(storeName: String, website: String) = true
}

object ChainSourceAdapters {
    private val adapters = listOf(
        EsselungaSourceAdapter(), CoopSourceAdapter(), ConadSourceAdapter(), LidlSourceAdapter(),
        EurospinSourceAdapter(), CarrefourSourceAdapter(), GenericSourceAdapter(),
    )

    fun forStore(storeName: String, website: String): ChainSourceAdapter =
        adapters.first { it.matches(storeName, website) }
}
