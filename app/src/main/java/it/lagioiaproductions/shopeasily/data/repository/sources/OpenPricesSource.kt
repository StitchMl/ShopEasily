package it.lagioiaproductions.shopeasily.data.repository.sources

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Open Prices (prices.openfoodfacts.org) is an open, crowdsourced database of
 * price tags and receipts, linked to OpenStreetMap shops. It is the only public
 * source that reliably covers small independent shops, markets and farm shops,
 * which usually have no e-commerce site or flyer. Data licence: ODbL.
 */
object OpenPricesSource {
    const val PARSER_ID = "open-prices"
    private const val BASE = "https://prices.openfoodfacts.org/api/v1/prices"
    private const val MAX_AGE_DAYS = 120L

    fun url(osmType: String, osmId: Long, now: Long = System.currentTimeMillis()): String {
        val since = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(now - MAX_AGE_DAYS * 24 * 60 * 60 * 1_000))
        return "$BASE?location_osm_type=${osmType.uppercase(Locale.ROOT)}&location_osm_id=$osmId" +
            "&date__gte=$since&order_by=-date&size=100"
    }

    fun parse(json: String, sourceUrl: String): List<SourceProduct> {
        val items = runCatching { JSONObject(json).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val currency = item.optNullableString("currency")
                if (currency != null && currency != "EUR") continue
                val price = item.optDouble("price", Double.NaN)
                if (price.isNaN() || price <= 0.0 || price > 500.0) continue
                val product = item.optJSONObject("product")
                val baseName = product?.optNullableString("product_name")
                    ?: item.optNullableString("product_name")
                    ?: item.optNullableString("category_tag")?.let(::categoryName)
                    ?: continue
                val quantity = product?.optNullableString("quantity")
                val per = when (item.optNullableString("price_per")) {
                    "KILOGRAM" -> "al kg"
                    "LITER" -> "al litro"
                    else -> null
                }
                val name = listOfNotNull(baseName.trim(), quantity?.takeIf { !baseName.contains(it, true) }, per)
                    .joinToString(" ")
                val labels = (tags(product?.optJSONArray("labels_tags")) + tags(item.optJSONArray("labels_tags")))
                    .mapNotNull(::labelName).distinct()
                add(
                    SourceProduct(
                        name = name.replaceFirstChar { it.titlecase(Locale.ITALY) },
                        price = price,
                        imageUrl = product?.optNullableString("image_url"),
                        promotional = item.optBoolean("price_is_discounted", false),
                        sourceUrl = sourceUrl,
                        labels = labels,
                        parserId = PARSER_ID,
                    ),
                )
            }
        }.distinctBy { it.name.lowercase(Locale.ROOT) to it.price }
    }

    private fun tags(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }

    private fun labelName(tag: String): String? {
        val value = tag.lowercase(Locale.ROOT)
        return when {
            value.contains("organic") || value.contains("bio") -> "Biologico"
            value.contains("fair-trade") || value.contains("fairtrade") -> "Equosolidale"
            value.contains("free-range") || value.contains("animal-welfare") -> "Benessere animale"
            value.contains("km-0") || value.contains("local") -> "Filiera locale"
            else -> null
        }
    }

    private val categoryNames = mapOf(
        "apples" to "Mele", "pears" to "Pere", "bananas" to "Banane", "oranges" to "Arance",
        "lemons" to "Limoni", "mandarins" to "Mandarini", "clementines" to "Clementine", "kiwis" to "Kiwi",
        "grapes" to "Uva", "peaches" to "Pesche", "apricots" to "Albicocche", "strawberries" to "Fragole",
        "cherries" to "Ciliegie", "melons" to "Meloni", "watermelons" to "Angurie", "plums" to "Prugne",
        "tomatoes" to "Pomodori", "potatoes" to "Patate", "carrots" to "Carote", "onions" to "Cipolle",
        "zucchini" to "Zucchine", "courgettes" to "Zucchine", "eggplants" to "Melanzane", "aubergines" to "Melanzane",
        "peppers" to "Peperoni", "sweet-peppers" to "Peperoni", "lettuces" to "Insalata", "salads" to "Insalata",
        "spinachs" to "Spinaci", "spinach" to "Spinaci", "broccoli" to "Broccoli", "cauliflowers" to "Cavolfiori",
        "cabbages" to "Cavoli", "fennels" to "Finocchi", "celery" to "Sedano", "pumpkins" to "Zucca",
        "cucumbers" to "Cetrioli", "garlic" to "Aglio", "mushrooms" to "Funghi", "artichokes" to "Carciofi",
        "leeks" to "Porri", "beans" to "Fagioli", "eggs" to "Uova", "bread" to "Pane", "breads" to "Pane",
    )

    private fun categoryName(tag: String): String {
        val key = tag.substringAfter(':').lowercase(Locale.ROOT)
        return categoryNames[key] ?: key.replace('-', ' ')
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
}
