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
        // Any site: offer tiles built with custom markup (e.g. "Rummo Pasta 500 g – € 0,79").
        val loose = if (cards.isEmpty()) parseLooseBlocks(document) else emptyList()
        return (cards + listOfNotNull(detail) + loose).distinctBy { Triple(it.name.lowercase(), it.price, it.imageUrl) }
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

    /**
     * Template-independent extraction: starts from every text that looks like a price
     * and climbs to the smallest block that also contains a product-like title.
     * Two prices in the same tile mean "was/now": the lower one is the offer price.
     */
    private fun parseLooseBlocks(document: org.jsoup.nodes.Document): List<HtmlProduct> {
        val priceElements = document.body()?.select("*:matchesOwn(\\d{1,4}[,.]\\d{2})")?.take(MAX_PRICE_NODES) ?: return emptyList()
        return priceElements.filterNot(::inBoilerplate).mapNotNull { priceElement ->
            var block: Element? = priceElement
            for (level in 0 until 5) {
                val current = block ?: break
                if (current.text().length > 20 && titleIn(current, priceElement) != null) break
                block = current.parent()
            }
            val tile = block ?: return@mapNotNull null
            if (tile.text().length > 400) return@mapNotNull null
            val prices = pricePattern.findAll(tile.text()).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                .filter { it in 0.05..500.0 }.toList()
            if (prices.isEmpty() || prices.size > 3) return@mapNotNull null
            if (!tile.text().contains('€') && !tile.text().contains("eur", ignoreCase = true)) return@mapNotNull null
            val name = composedName(tile) ?: titleIn(tile, priceElement) ?: return@mapNotNull null
            // A free-form tile must look like a real product: a quantity ("500 g", "1 L", "6 pz")
            // or a grocery word; a high price without any quantity is a misread (e.g. 50,00 €).
            val hasQuantity = QUANTITY.containsMatchIn(name) || QUANTITY.containsMatchIn(tile.text())
            if (!hasQuantity && !ProductVocabulary.looksLikeGrocery(name)) return@mapNotNull null
            if (!hasQuantity && prices.min() > 40.0) return@mapNotNull null
            // The picture is often a sibling of the text block: climb while the tile stays one product.
            var imageHolder: Element = tile
            for (level in 0 until 2) {
                if (imageHolder.selectFirst("img") != null) break
                val parent = imageHolder.parent() ?: break
                if (pricePattern.findAll(parent.text()).count() != prices.size || parent.text().length > 400) break
                imageHolder = parent
            }
            val image = imageHolder.selectFirst("img")?.let(::imageUrl)
            HtmlProduct(name, prices.min(), image, tile.selectFirst("a[href]")?.absUrl("href")?.takeIf(String::isNotBlank))
        }.distinctBy { it.name.lowercase() to it.price }
    }

    /**
     * Offer tiles often split the title over several lines: "Rummo" / "Pasta di semola" /
     * "formati normali g 500". Joins the text lines in order, skipping price lines
     * ("€ 0,79", "Anziché € 1,19", "al Kg € 1,58", "-30%").
     */
    private fun composedName(tile: Element): String? {
        val fragments = tile.getAllElements().asSequence()
            .map { it.ownText().trim() }
            .filter { it.isNotBlank() && it.any(Char::isLetter) }
            .filterNot { text -> PRICE_LINE.containsMatchIn(text) || PRICE_WORDS.any { text.startsWith(it, ignoreCase = true) } }
            .map { it.replace(Regex("\\s+"), " ") }
            .distinct()
            .take(4)
            .toList()
        if (fragments.isEmpty()) return null
        val words = mutableListOf<String>()
        for (fragment in fragments) {
            val next = fragment.split(' ')
            if (words.size + next.size > 12) break
            words += next
        }
        val name = words.joinToString(" ").take(100).trim()
        return name.takeIf { it.length >= 3 && OfferTextParser.looksLikeProductName(it) }
    }

    /** Header, footer, menus, forms, cookie banners, cart widgets: never product tiles. */
    private fun inBoilerplate(element: Element): Boolean = element.parents().any { parent ->
        parent.tagName() in setOf("header", "footer", "nav", "aside", "form", "select", "option", "button") ||
            BOILERPLATE_HINTS.any { hint ->
                parent.id().contains(hint, ignoreCase = true) || parent.className().contains(hint, ignoreCase = true)
            }
    }

    private val BOILERPLATE_HINTS = listOf(
        "footer", "header", "navbar", "menu", "cookie", "gdpr", "consent", "banner", "newsletter",
        "breadcrumb", "minicart", "cart", "carrello", "modal", "popup", "privacy", "copyright", "social",
    )
    private val QUANTITY = Regex("""\b\d+([.,]\d+)?\s?(g|gr|kg|ml|cl|l|lt|pz|pezzi|conf|x\s?\d+)\b""", RegexOption.IGNORE_CASE)

    private val PRICE_LINE = Regex("""\d{1,4}[,.]\d{2}|€|%""")
    private val PRICE_WORDS = listOf("anziché", "anziche", "invece di", "al kg", "al lt", "al litro", "prezzo", "sconto", "offerta valida", "fino al", "dal ")

    private fun titleIn(block: Element, priceElement: Element): String? =
        block.select("h1, h2, h3, h4, h5, h6, strong, b, [class*=title], [class*=name], [class*=nome], [class*=descr], p, span, a")
            .asSequence()
            .filter { it != priceElement && !it.text().contains(Regex("\\d[,.]\\d{2}")) }
            .map { it.ownText().ifBlank { it.text() }.trim() }
            .firstOrNull { it.length in 3..120 && OfferTextParser.looksLikeProductName(it) }

    private const val MAX_PRICE_NODES = 400

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
