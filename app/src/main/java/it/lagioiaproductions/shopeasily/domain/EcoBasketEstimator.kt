package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import java.util.Locale
import kotlin.math.roundToInt

data class EcoStoreCandidate(
    val id: String,
    val name: String,
    val distanceMeters: Int,
    val greenScore: Int,
    val observedQualityScore: Int? = null,
)

data class EcoItemEstimate(
    val requestedItem: String,
    val estimatedPrice: Double,
    /** Number of observed comparable prices used; zero means category fallback. */
    val observations: Int,
)

data class EcoBasketEstimate(
    val storeId: String,
    val storeName: String,
    val greenScore: Int,
    val items: List<EcoItemEstimate>,
    val productsTotal: Double,
    val travelCost: Double,
    val emissionKg: Double,
    val qualityScore: Int,
    val qualityFromReviews: Boolean,
) {
    val total: Double = productsTotal + travelCost
    val lowConfidenceItems: Int = items.count { it.observations < 3 }
}

/**
 * Estimates a basket only when a green shop has no usable public list price.
 * Estimates are never converted to offers or persisted as observed prices.
 */
object EcoBasketEstimator {
    fun estimate(
        requestedItems: List<String>,
        catalog: List<CatalogPrice>,
        stores: List<EcoStoreCandidate>,
        transport: TransportProfile,
        minimumGreenScore: Int = StoreSustainabilityResult.LEAF_THRESHOLD,
    ): List<EcoBasketEstimate> {
        val requested = requestedItems.map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (requested.isEmpty()) return emptyList()

        val eligible = stores.filter { it.greenScore >= minimumGreenScore }
        return eligible.map { store ->
            val items = requested.map { item -> estimateItem(item, catalog) }
            val roundTripKm = store.distanceMeters.coerceAtLeast(0) * 2.0 / 1_000.0
            EcoBasketEstimate(
                storeId = store.id,
                storeName = store.name,
                greenScore = store.greenScore,
                items = items,
                productsTotal = items.sumOf(EcoItemEstimate::estimatedPrice),
                travelCost = roundTripKm * transport.costPerKm(),
                emissionKg = roundTripKm * transport.emissionKgPerKm(),
                qualityScore = store.observedQualityScore
                    ?: (45 + store.greenScore.coerceIn(0, 100) * 0.4).roundToInt().coerceIn(0, 100),
                qualityFromReviews = store.observedQualityScore != null,
            )
        }.sortedWith(
            compareByDescending<EcoBasketEstimate> { it.greenScore }
                .thenBy { it.emissionKg }
                .thenBy { it.total },
        )
    }

    private fun estimateItem(item: String, catalog: List<CatalogPrice>): EcoItemEstimate {
        val prices = catalog.asSequence()
            .filter { it.price.isFinite() && it.price > 0.05 && ProductMatcher.matches(it.productName, item) }
            .map(CatalogPrice::price)
            .sorted()
            .toList()
        val observed = median(prices)
        // A small conservative uplift avoids presenting an optimistic supermarket offer
        // as the expected shelf price of an independent/local seller.
        val estimate = observed?.times(if (prices.size >= 3) 1.08 else 1.12) ?: categoryFallback(item)
        return EcoItemEstimate(item, estimate, prices.size)
    }

    private fun median(values: List<Double>): Double? = when {
        values.isEmpty() -> null
        values.size % 2 == 1 -> values[values.size / 2]
        else -> (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
    }

    private fun categoryFallback(item: String): Double {
        val value = item.lowercase(Locale.ROOT)
        return when {
            listOf("acqua", "sale").any(value::contains) -> 1.50
            listOf("latte", "yogurt", "pasta", "riso", "legumi").any(value::contains) -> 2.20
            listOf("frutta", "verdura", "ortaggi").any(value::contains) -> 3.50
            listOf("carne", "pesce", "formaggio").any(value::contains) -> 8.00
            listOf("birra", "vino", "olio").any(value::contains) -> 5.00
            listOf("gatto", "cane", "pappa", "detersivo", "scottex", "quasar").any(value::contains) -> 4.50
            else -> 3.50
        }
    }
}
