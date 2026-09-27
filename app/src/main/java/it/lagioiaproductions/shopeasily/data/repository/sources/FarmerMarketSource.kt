package it.lagioiaproductions.shopeasily.data.repository.sources

import it.lagioiaproductions.shopeasily.data.repository.BrandDirectory
import it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator
import it.lagioiaproductions.shopeasily.data.repository.net.HttpFetcher
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray

/**
 * Farmers' markets rarely have a website of their own, but the Coldiretti network
 * "Campagna Amica" publishes a page for each market and, for many of them, an
 * online shop on a sub-domain (e.g. spesaromacircomassimo.campagnamica.it) with the
 * farmers' real prices. This source finds them automatically for each market found
 * on OpenStreetMap/Google: WordPress search on campagnamica.it → market page whose
 * text names the market's place → links to its shop sub-domain.
 */
class FarmerMarketSource(private val http: HttpFetcher) {

    fun shopSites(marketName: String, place: String): List<String> {
        val placeTokens = BrandDirectory.locationTokens(place)
        val terms = (searchTokens(marketName) + placeTokens.take(2)).distinct().joinToString(" ")
        if (terms.isBlank()) return emptyList()
        val searchUrl = "$NETWORK/wp-json/wp/v2/search?per_page=8&search=" + URLEncoder.encode(terms, Charsets.UTF_8.name())
        val results = http.politeGet(searchUrl, HttpFetcher.MAX_JSON_BYTES, accept = "application/json")
            ?.toString(Charsets.UTF_8)?.let(::parseSearch).orEmpty()
        return results
            .filter { (url, title) -> url.contains("/mercat", true) || title.contains("mercato", true) }
            .take(4)
            .flatMap { (url, _) ->
                val html = http.politeGet(url)?.toString(Charsets.UTF_8) ?: return@flatMap emptyList()
                if (placeTokens.isNotEmpty() && !BrandDirectory.textMentionsPlace(html, placeTokens)) return@flatMap emptyList()
                shopLinks(html)
            }
            .distinct()
            .take(2)
    }

    companion object {
        private const val NETWORK = "https://www.campagnamica.it"
        private val SHOP_LINK = Regex("""https?://([a-z0-9-]+)\.campagnamica\.it/?""", RegexOption.IGNORE_CASE)
        private val networkWords = setOf("campagna", "amica", "mercato", "mercati", "coldiretti", "contadino", "contadini")

        fun isFarmerMarket(name: String, category: String): Boolean {
            val lower = name.lowercase(Locale.ITALIAN)
            return category == "marketplace" || category == "farm" ||
                listOf("campagna amica", "coldiretti", "contadin", "km 0", "km0", "mercato").any(lower::contains)
        }

        fun searchTokens(name: String): List<String> =
            StoreDeduplicator.canonicalName(name).split(' ').filter { it.length >= 3 && it !in networkWords }

        fun parseSearch(json: String): List<Pair<String, String>> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                url to item.optString("title")
            }
        }.getOrDefault(emptyList())

        /** Links to the market's own shop sub-domain ("spesa…"), never the main portal. */
        fun shopLinks(html: String): List<String> = SHOP_LINK.findAll(html)
            .map { it.groupValues[1].lowercase(Locale.ROOT) }
            .filter { it != "www" && (it.startsWith("spesa") || it.startsWith("mercato") || it.startsWith("shop")) }
            .distinct()
            .map { "https://$it.campagnamica.it" }
            .toList()
    }
}
