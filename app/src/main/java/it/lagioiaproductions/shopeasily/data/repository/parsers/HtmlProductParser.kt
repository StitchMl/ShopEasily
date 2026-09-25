package it.lagioiaproductions.shopeasily.data.repository.parsers

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class HtmlProduct(
    val name: String,
    val price: Double,
    val imageUrl: String?,
    val detailUrl: String?,
)

object HtmlProductParser {
    private val pricePattern = Regex("""(?:€\s*)?(\d{1,4}[,.]\d{2})(?:\s*€)?""")
    private val containers = listOf(
        "[itemtype*=Product]", "[data-product]", "[data-product-id]",
        ".product-card", ".product-item", ".product-tile", "article.product",
    ).joinToString(",")
    private val nameSelectors = listOf("[itemprop=name]", ".product-name", ".product-title", "h2", "h3")
    private val priceSelectors = listOf("[itemprop=price]", "[data-price]", ".price", ".product-price")

    fun parse(html: String, baseUrl: String): List<HtmlProduct> {
        val document = Jsoup.parse(html, baseUrl)
        val cards = document.select(containers).mapNotNull(::parseCard)
        val detail = parseProductPage(document, baseUrl)
        return (cards + listOfNotNull(detail)).distinctBy { Triple(it.name.lowercase(), it.price, it.imageUrl) }
    }

    fun detailLinks(html: String, baseUrl: String): List<String> = Jsoup.parse(html, baseUrl)
        .select(containers).mapNotNull { card -> card.selectFirst("a[href]")?.absUrl("href") }
        .filter { it.startsWith("http") }.distinct()

    private fun parseCard(card: Element): HtmlProduct? {
        if (card.text().length > 1_200) return null
        val name = nameSelectors.firstNotNullOfOrNull { selector ->
            card.selectFirst(selector)?.text()?.trim()?.takeIf { it.length in 3..180 }
        } ?: return null
        val priceElement = priceSelectors.firstNotNullOfOrNull(card::selectFirst) ?: return null
        val priceText = priceElement.attr("content").ifBlank { priceElement.attr("data-price") }
            .ifBlank { priceElement.text() }
        val price = parsePrice(priceText) ?: return null
        val image = card.selectFirst("[itemprop=image], img")?.let(::imageUrl)
        val detailUrl = card.selectFirst("a[href]")?.absUrl("href")?.takeIf(String::isNotBlank)
        return HtmlProduct(name, price, image, detailUrl)
    }

    private fun parseProductPage(document: org.jsoup.nodes.Document, baseUrl: String): HtmlProduct? {
        val name = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.length in 3..180 } ?: return null
        val price = document.selectFirst("meta[property=product:price:amount], meta[itemprop=price]")
            ?.let { parsePrice(it.attr("content")) } ?: return null
        val image = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { resolve(baseUrl, it) }
        return HtmlProduct(name, price, image, baseUrl)
    }

    private fun imageUrl(element: Element): String? {
        val raw = element.attr("content").ifBlank { element.attr("data-src") }
            .ifBlank { element.attr("src") }.ifBlank { element.attr("srcset").substringBefore(' ') }
        return raw.takeIf(String::isNotBlank)?.let { element.baseUri().let { base -> resolve(base, raw) } }
    }

    private fun parsePrice(value: String): Double? = pricePattern.find(value)?.groupValues?.get(1)
        ?.replace(',', '.')?.toDoubleOrNull()
        ?.takeIf { it in 0.01..100_000.0 }

    private fun resolve(base: String, value: String): String? = runCatching {
        URI(base).resolve(value).toString().takeIf { it.startsWith("http") }
    }.getOrNull()
}
