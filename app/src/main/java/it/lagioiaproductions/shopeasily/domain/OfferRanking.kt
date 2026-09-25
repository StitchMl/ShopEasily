package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.OfferDay
import it.lagioiaproductions.shopeasily.data.model.isActiveOn
import it.lagioiaproductions.shopeasily.data.model.isEligibleFor
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
                    filters.includeLoyaltyOffers && offer.storeName in filters.loyaltyCards
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
            SortMode.QUALITY -> candidates.sortedWith(
                compareByDescending<Offer>(Offer::effectiveQualityScore).thenBy { it.unitPriceOrPrice() },
            )
            SortMode.SMART -> smartSort(candidates)
        }
    }

    private fun smartSort(offers: List<Offer>): List<Offer> {
        if (offers.isEmpty()) return emptyList()

        val maximumPrice = offers.maxOf { it.unitPriceOrPrice() }.coerceAtLeast(0.01)
        val maximumDistance = offers.maxOf(Offer::distanceMeters).coerceAtLeast(1)

        return offers.sortedByDescending { offer ->
            val priceScore = 1.0 - offer.unitPriceOrPrice() / maximumPrice
            val distanceScore = 1.0 - offer.distanceMeters.toDouble() / maximumDistance
            val qualityScore = offer.effectiveQualityScore().toDouble() / 5.0
            val sustainabilityScore = offer.sustainabilityScore() / 100.0
            val loyaltyPenalty = if (offer.loyaltyRequired) 0.08 else 0.0

            0.45 * priceScore +
                0.20 * distanceScore +
                0.20 * qualityScore +
                0.15 * sustainabilityScore -
                loyaltyPenalty
        }
    }

    private fun Offer.unitPriceOrPrice(): Double = unitPrice ?: price

    private const val SUSTAINABILITY_BAND = 20
}

/** Explicit rating when available; otherwise a conservative data-quality estimate. */
fun Offer.effectiveQualityScore(): Float = qualityScore ?: (
    2.2f +
        (if (productImageVerified) 0.8f else 0f) +
        (if (!productImageUrl.isNullOrBlank()) 0.25f else 0f) +
        (if (!brand.isNullOrBlank()) 0.45f else 0f) +
        (if (productName.length in 8..80) 0.3f else 0f) +
        (if (sustainabilityLabels.isNotEmpty()) 0.5f else 0f)
    ).coerceIn(1f, 5f)

fun Offer.sustainabilityScore(): Int {
    val labels = sustainabilityLabels.joinToString(" ").lowercase()
    val certification = when {
        listOf("biologic", "bio ", "fair", "equo", "cruelty", "benessere animale").any(labels::contains) -> 60
        listOf("km 0", "locale", "filiera", "stagional", "artigian").any(labels::contains) -> 45
        sustainabilityLabels.isNotEmpty() -> 35
        else -> 0
    }
    val proximity = when {
        distanceMeters <= 500 -> 30
        distanceMeters <= 2_000 -> 24
        distanceMeters <= 5_000 -> 16
        distanceMeters <= 10_000 -> 8
        else -> 2
    }
    val quality = (effectiveQualityScore() / 5f * 10).toInt()
    return (certification + proximity + quality).coerceIn(0, 100)
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
