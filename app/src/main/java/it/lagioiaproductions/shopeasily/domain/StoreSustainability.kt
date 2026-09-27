package it.lagioiaproductions.shopeasily.domain

import java.text.Normalizer
import java.util.Locale

data class StoreSustainabilityResult(
    /** 0..100 leaf score of the point of sale itself. */
    val score: Int,
    /** Human-readable evidence, shown to the user and persisted with the store. */
    val reasons: List<String>,
) {
    val hasLeaf: Boolean get() = score >= LEAF_THRESHOLD

    companion object {
        const val LEAF_THRESHOLD = 40
    }
}

/**
 * Computes the store leaf score from public OpenStreetMap tags and from the
 * identity of well-known organic / fair-trade / bulk retailers.
 *
 * Previously the leaf was a boolean that looked only at `organic=yes|only`,
 * `fair_trade=yes` and `produce=local`: `organic=limited`, `fair_trade=only`,
 * farm shops, farmers' markets, bulk/zero-waste shops and all-organic chains
 * such as NaturaSì never received it, and the value never reached the products.
 */
object StoreSustainability {
    // Generic Italian words only: no list of specific retailers to maintain.
    private val organicWords = listOf("biologico", "biologici", "biologica", "bio ", " bio", "biobottega", "naturale bio")
    private val fairTradeWords = listOf("bottega del mondo", "botteghe del mondo", "commercio equo", "equo e solidale", "equosolidale", "fairtrade", "fair trade")
    private val bulkWords = listOf("sfuso", "sfusi", "zero waste", "rifiuti zero", "alla spina", "negozio leggero")
    private val localWords = listOf("km 0", "km0", "chilometro zero", "azienda agricola", "fattoria", "contadin", "agricola", "caseificio", "filiera corta", "mercato contadino", "campagna amica")

    /** Tags that a brand's branches share: a missing value is inherited from sibling branches. */
    val INHERITABLE_TAGS = listOf("organic", "fair_trade", "bulk_purchase", "zero_waste")
    private val strength = mapOf("only" to 3, "yes" to 2, "limited" to 1)

    /** Strongest value of [key] among the branches of the same brand. */
    fun strongest(values: Collection<String?>): String? =
        values.filterNotNull().maxByOrNull { strength[it.lowercase(Locale.ROOT)] ?: 0 }
            ?.takeIf { (strength[it.lowercase(Locale.ROOT)] ?: 0) > 0 }

    fun evaluate(
        name: String,
        category: String,
        tags: Map<String, String>,
        isChain: Boolean = !tags["brand"].isNullOrBlank() || !tags["brand:wikidata"].isNullOrBlank(),
        brandDescription: String? = null,
    ): StoreSustainabilityResult {
        val reasons = mutableListOf<String>()
        var score = 0
        fun add(points: Int, reason: String) {
            score += points
            if (reason !in reasons) reasons += reason
        }
        val normalizedName = " " + normalize(name + " " + tags["brand"].orEmpty()) + " "
        val description = brandDescription?.lowercase(Locale.ROOT).orEmpty()
        val shop = tags["shop"] ?: category

        // Organic offer.
        when (tags["organic"]?.lowercase(Locale.ROOT)) {
            "only" -> add(50, "Solo prodotti biologici")
            "yes" -> add(30, "Prodotti biologici")
            "limited" -> add(15, "Alcuni prodotti biologici")
        }
        if (shop in setOf("organic", "health_food") && tags["organic"] == null) add(35, "Negozio biologico")
        if (tags["organic"] != "only" && (organicWords.any(normalizedName::contains) ||
                listOf("biologic", "organic").any(description::contains))
        ) add(40, "Insegna biologica")

        // Fair trade.
        when (tags["fair_trade"]?.lowercase(Locale.ROOT)) {
            "only" -> add(45, "Commercio equo e solidale")
            "yes" -> add(25, "Prodotti equosolidali")
            "limited" -> add(10, "Alcuni prodotti equosolidali")
        }
        if (tags["fair_trade"] != "only" && (fairTradeWords.any(normalizedName::contains) ||
                listOf("commercio equo", "equosolidal", "fair trade").any(description::contains))
        ) add(40, "Bottega equosolidale")

        // Short supply chain.
        if (shop == "farm") add(45, "Vendita diretta del produttore")
        if (tags["amenity"] == "marketplace" || shop == "marketplace") add(25, "Mercato rionale")
        if (tags["produce"]?.contains("local", ignoreCase = true) == true || tags["local_produce"] == "yes") {
            add(25, "Prodotti locali")
        }
        if (localWords.any(normalizedName::contains)) add(25, "Filiera corta")

        // Packaging / waste.
        when (tags["bulk_purchase"]?.lowercase(Locale.ROOT)) {
            "only" -> add(40, "Solo prodotti sfusi")
            "yes" -> add(20, "Prodotti sfusi")
        }
        when (tags["zero_waste"]?.lowercase(Locale.ROOT)) {
            "only" -> add(40, "Negozio a rifiuti zero")
            "yes" -> add(20, "Riduzione rifiuti")
        }
        if (tags["reusable_packaging:accept"] == "yes" || tags["reusable_packaging:offer"] == "yes") {
            add(10, "Contenitori riutilizzabili")
        }
        if (bulkWords.any(normalizedName::contains) && tags["bulk_purchase"] == null && tags["zero_waste"] == null) {
            add(25, "Prodotti sfusi")
        }

        // Independent neighbourhood food shop: small but real bonus.
        if (!isChain && shop in setOf("greengrocer", "butcher", "bakery", "cheese", "dairy", "deli", "seafood", "pastry", "farm")) {
            add(10, "Negozio di vicinato indipendente")
        }

        return StoreSustainabilityResult(score.coerceIn(0, 100), reasons)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
