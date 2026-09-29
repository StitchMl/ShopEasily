package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.repository.NearbyStore
import it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator
import kotlin.math.roundToInt

data class StoreAssessment(
    val qualityScore: Int?,
    val valueScore: Int?,
    val reviewRating: Double?,
    val reviewCount: Int,
    val priceTendency: PriceTendency?,
    val priceObservations: Int,
)

enum class PriceTendency { LOW, AVERAGE, HIGH }

/** Transparent store-level indicators; missing evidence stays missing instead of becoming a fake score. */
object StoreAssessmentEngine {
    fun assess(store: NearbyStore, catalog: List<CatalogPrice>): StoreAssessment {
        val quality = store.reviewRating?.let { rating ->
            // Bayesian shrinkage: a 5.0 from two reviews must not beat a proven 4.7 from 500.
            val count = store.reviewCount.coerceAtLeast(0)
            val adjusted = (rating * count + REVIEW_PRIOR * PRIOR_WEIGHT) / (count + PRIOR_WEIGHT)
            (adjusted / 5.0 * 100).roundToInt().coerceIn(0, 100)
        }
        val storePrices = catalog.filter { candidate ->
            StoreDeduplicator.belongsToBrand(candidate.store.name, store.name)
        }
        val observedRatios = storePrices.mapNotNull { own ->
            val comparable = catalog.asSequence()
                .filter { it.price.isFinite() && it.price > 0.05 && ProductMatcher.matches(it.productName, own.productName) }
                .map(CatalogPrice::price).sorted().toList()
            median(comparable)?.takeIf { it > 0.0 }?.let { own.price / it }
        }
        val observedScore = median(observedRatios)?.let { ratio ->
            (100.0 / ratio.coerceAtLeast(0.5)).roundToInt().coerceIn(0, 100)
        }
        val levelScore = when (store.priceLevel) {
            "PRICE_LEVEL_FREE" -> 100
            "PRICE_LEVEL_INEXPENSIVE" -> 85
            "PRICE_LEVEL_MODERATE" -> 60
            "PRICE_LEVEL_EXPENSIVE" -> 30
            "PRICE_LEVEL_VERY_EXPENSIVE" -> 10
            else -> null
        }
        val value = when {
            observedScore != null && levelScore != null -> (observedScore * 0.7 + levelScore * 0.3).roundToInt()
            observedScore != null -> observedScore
            else -> levelScore
        }
        val tendency = value?.let {
            when {
                it >= 72 -> PriceTendency.LOW
                it >= 48 -> PriceTendency.AVERAGE
                else -> PriceTendency.HIGH
            }
        }
        return StoreAssessment(quality, value, store.reviewRating, store.reviewCount, tendency, observedRatios.size)
    }

    private fun median(values: List<Double>): Double? = values.sorted().let { sorted ->
        when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
    }

    private const val REVIEW_PRIOR = 3.8
    private const val PRIOR_WEIGHT = 20
}
