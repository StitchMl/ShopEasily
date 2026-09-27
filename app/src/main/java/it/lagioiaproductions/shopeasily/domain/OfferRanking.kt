package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.OfferDay
import it.lagioiaproductions.shopeasily.data.model.isActiveOn
import it.lagioiaproductions.shopeasily.data.model.isEligibleFor
import it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator
import java.util.Calendar

enum class SortMode(val label: String) {
    SMART("Consigliati"),
    PRICE("Prezzo"),
    DISTANCE("Distanza"),
    QUALITY("Qualità"),
}

data class SearchFilters(
    val sortMode: SortMode = SortMode.SMART,
    val sustainableOnly: Boolean = false,
    val includeLoyaltyOffers: Boolean = true,
    val maximumDistanceMeters: Int = 10_000,
    val userAge: Int? = null,
    val loyaltyCards: Set<String> = emptySet(),
    val day: OfferDay = currentOfferDay(),
)

object OfferRanking {
    fun apply(offers: List<Offer>, filters: SearchFilters): List<Offer> {
        val filtered = offers.filter { offer ->
            offer.distanceMeters <= filters.maximumDistanceMeters &&
                (!offer.loyaltyRequired || (
                    filters.includeLoyaltyOffers && filters.loyaltyCards.any { card ->
                        card.equals(offer.storeName, ignoreCase = true) ||
                            StoreDeduplicator.belongsToBrand(offer.storeName, card)
                    }
                )) &&
                offer.isActiveOn(filters.day) &&
                offer.isEligibleFor(filters.userAge)
        }

        val candidates = if (filters.sustainableOnly && filtered.isNotEmpty()) {
            val bestScore = filtered.maxOf(Offer::sustainabilityScore)
            filtered.filter { it.sustainabilityScore() >= bestScore - SUSTAINABILITY_BAND }
        } else {
            filtered
        }
        return when (filters.sortMode) {
            SortMode.PRICE -> candidates.sortedWith(compareBy<Offer> { it.unitPriceOrPrice() }.thenBy(Offer::distanceMeters))
            SortMode.DISTANCE -> candidates.sortedWith(compareBy(Offer::distanceMeters).thenBy { it.unitPriceOrPrice() })
            SortMode.QUALITY -> candidates.map { it to it.effectiveQualityScore() }
                .sortedWith(compareByDescending<Pair<Offer, Float>> { it.second }.thenBy { it.first.unitPriceOrPrice() })
                .map { it.first }
            SortMode.SMART -> smartSort(candidates)
        }
    }

    private fun smartSort(offers: List<Offer>): List<Offer> {
        if (offers.isEmpty()) return emptyList()
        // 95th percentile instead of the maximum: one 400 € item must not flatten all prices.
        fun percentile(values: List<Double>): Double = values.sorted().let { it[((it.size - 1) * 0.95).toInt()] }
        val referencePrice = percentile(offers.map { it.unitPriceOrPrice() }).coerceAtLeast(0.01)
        val referenceDistance = percentile(offers.map { it.distanceMeters.toDouble() }).coerceAtLeast(1.0)

        // Scores are computed once per offer, not at every comparison of the sort.
        return offers.map { offer ->
            val priceScore = (1.0 - offer.unitPriceOrPrice() / referencePrice).coerceIn(0.0, 1.0)
            val distanceScore = (1.0 - offer.distanceMeters / referenceDistance).coerceIn(0.0, 1.0)
            val qualityScore = offer.effectiveQualityScore().toDouble() / 5.0
            val sustainabilityScore = offer.sustainabilityScore() / 100.0
            val loyaltyPenalty = if (offer.loyaltyRequired) 0.08 else 0.0
            offer to (0.45 * priceScore + 0.20 * distanceScore + 0.20 * qualityScore + 0.15 * sustainabilityScore - loyaltyPenalty)
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun Offer.unitPriceOrPrice(): Double = unitPrice ?: price

    private const val SUSTAINABILITY_BAND = 20
}

private val QUANTITY_IN_NAME = Regex("\\b\\d+([.,]\\d+)?\\s?(g|kg|ml|l|cl|pz)\\b", RegexOption.IGNORE_CASE)

/** Explicit rating when available; otherwise a conservative data-quality estimate. */
fun Offer.effectiveQualityScore(): Float = qualityScore ?: (
    2.0f +
        (if (productImageVerified) 0.7f else 0f) +
        (if (!productImageUrl.isNullOrBlank()) 0.25f else 0f) +
        (if (!brand.isNullOrBlank()) 0.45f else 0f) +
        (if (productName.length in 8..80) 0.3f else 0f) +
        // Each certification/label adds evidence (bio, fair trade, animal welfare, local).
        (sustainabilityLabels.size.coerceAtMost(3) * 0.35f) +
        // Weight/volume in the title = a precise product description, not a vague flyer line.
        (if (QUANTITY_IN_NAME.containsMatchIn(productName)) 0.25f else 0f)
    ).coerceIn(1f, 5f)

/**
 * Leaf score (0..100) of an offer. It is an index, not a certification:
 * - up to 45 points from the product's own labels (bio, fair trade, animal welfare, local chain);
 * - up to 35 points from the point of sale (store leaf score × 0.35);
 * - up to 15 points from proximity (less transport);
 * - up to 5 points from the available quality information.
 */
fun Offer.sustainabilityScore(): Int {
    val labels = sustainabilityLabels.joinToString(" ").lowercase()
    val product = when {
        listOf("biologic", "bio", "fair", "equo", "cruelty", "benessere animale").any(labels::contains) -> 45
        listOf("km 0", "locale", "filiera", "stagional", "artigian").any(labels::contains) -> 30
        sustainabilityLabels.isNotEmpty() -> 20
        else -> 0
    }
    val store = (storeSustainabilityScore.coerceIn(0, 100) * 0.35).toInt()
    val proximity = when {
        distanceMeters <= 500 -> 15
        distanceMeters <= 2_000 -> 12
        distanceMeters <= 5_000 -> 8
        distanceMeters <= 10_000 -> 4
        else -> 1
    }
    val quality = (effectiveQualityScore() / 5f * 5).toInt()
    return (product + store + proximity + quality).coerceIn(0, 100)
}

fun currentOfferDay(calendar: Calendar = Calendar.getInstance()): OfferDay = when (
    calendar.get(Calendar.DAY_OF_WEEK)
) {
    Calendar.MONDAY -> OfferDay.MONDAY
    Calendar.TUESDAY -> OfferDay.TUESDAY
    Calendar.WEDNESDAY -> OfferDay.WEDNESDAY
    Calendar.THURSDAY -> OfferDay.THURSDAY
    Calendar.FRIDAY -> OfferDay.FRIDAY
    Calendar.SATURDAY -> OfferDay.SATURDAY
    else -> OfferDay.SUNDAY
}
