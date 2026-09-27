package it.lagioiaproductions.shopeasily.data.repository.sources

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Public, documented product feeds of the e-commerce platforms most used by
 * small Italian shops, farms and organic/bulk stores:
 * - WooCommerce Store API: `/wp-json/wc/store/v1/products` (public, no key);
 * - Shopify storefront: `/products.json` (public product catalogue).
 * They give exact names and shelf prices without scraping HTML templates.
 */
object EcommerceApiSource {
    const val WOO_ID = "woocommerce-store-api"
    const val SHOPIFY_ID = "shopify-products-json"

    fun candidateUrls(website: String): List<Pair<String, String>> {
        val uri = runCatching { URI(website) }.getOrNull() ?: return emptyList()
        val host = uri.host ?: return emptyList()
        val origin = "https://$host"
        return listOf(
            WOO_ID to "$origin/wp-json/wc/store/v1/products?per_page=100",
            SHOPIFY_ID to "$origin/products.json?limit=250",
        )
    }

    fun parse(parserId: String, json: String, sourceUrl: String): List<SourceProduct> = runCatching {
        when (parserId) {
            WOO_ID -> parseWoo(JSONArray(json), sourceUrl)
            SHOPIFY_ID -> parseShopify(JSONObject(json), sourceUrl)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun parseWoo(products: JSONArray, sourceUrl: String): List<SourceProduct> = buildList {
        for (index in 0 until products.length()) {
            val product = products.optJSONObject(index) ?: continue
            if (!product.optBoolean("is_in_stock", true)) continue
            val prices = product.optJSONObject("prices") ?: continue
            val currency = prices.optString("currency_code", "EUR")
            if (currency.isNotBlank() && currency != "EUR") continue
            val minor = prices.optInt("currency_minor_unit", 2)
            val divisor = Math.pow(10.0, minor.toDouble())
            val price = prices.optString("price").toDoubleOrNull()?.div(divisor) ?: continue
            val regular = prices.optString("regular_price").toDoubleOrNull()?.div(divisor)
            val name = Jsoup.parse(product.optString("name")).text().trim()
            if (name.length < 3 || price <= 0.0) continue
            val image = product.optJSONArray("images")?.optJSONObject(0)?.optString("src")?.takeIf(String::isNotBlank)
            add(
                SourceProduct(
                    name = name,
                    price = price,
                    imageUrl = image,
                    promotional = product.optBoolean("on_sale", false) || (regular != null && regular > price),
                    sourceUrl = product.optString("permalink").takeIf(String::isNotBlank) ?: sourceUrl,
                    labels = labelsFrom(name + " " + categories(product)),
                    parserId = WOO_ID,
                ),
            )
        }
    }

    private fun parseShopify(root: JSONObject, sourceUrl: String): List<SourceProduct> = buildList {
        val products = root.optJSONArray("products") ?: return@buildList
        val origin = runCatching { URI(sourceUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault("")
        for (index in 0 until products.length()) {
            val product = products.optJSONObject(index) ?: continue
            val variants = product.optJSONArray("variants") ?: continue
            val variant = (0 until variants.length()).mapNotNull(variants::optJSONObject)
                .firstOrNull { it.optBoolean("available", true) } ?: continue
            val price = variant.optString("price").replace(',', '.').toDoubleOrNull() ?: continue
            val compareAt = variant.optString("compare_at_price").replace(',', '.').toDoubleOrNull()
            val title = product.optString("title").trim()
            val variantTitle = variant.optString("title").takeUnless { it.isBlank() || it.equals("Default Title", true) }
            val name = listOfNotNull(title, variantTitle).joinToString(" ")
            if (title.length < 3 || price <= 0.0) continue
            val image = product.optJSONArray("images")?.optJSONObject(0)?.optString("src")?.takeIf(String::isNotBlank)
            val handle = product.optString("handle")
            add(
                SourceProduct(
                    name = name,
                    price = price,
                    imageUrl = image,
                    promotional = compareAt != null && compareAt > price,
                    sourceUrl = if (handle.isNotBlank() && origin.isNotBlank()) "$origin/products/$handle" else sourceUrl,
                    labels = labelsFrom(name + " " + product.optString("tags")),
                    parserId = SHOPIFY_ID,
                ),
            )
        }
    }

    private fun categories(product: JSONObject): String {
        val array = product.optJSONArray("categories") ?: return ""
        return (0 until array.length()).joinToString(" ") { array.optJSONObject(it)?.optString("name").orEmpty() }
    }

    fun labelsFrom(text: String): List<String> = buildList {
        val value = text.lowercase(Locale.ROOT)
        if (Regex("\\b(bio|biologic[oaie]|organic)\\b").containsMatchIn(value)) add("Biologico")
        if (listOf("fairtrade", "fair trade", "equosolidal", "equo e solidale").any(value::contains)) add("Equosolidale")
        if (listOf("km 0", "km0", "filiera corta", "a chilometro zero", "del territorio").any(value::contains)) add("Filiera locale")
        if (listOf("sfuso", "alla spina", "vuoto a rendere").any(value::contains)) add("Sfuso")
    }
}
