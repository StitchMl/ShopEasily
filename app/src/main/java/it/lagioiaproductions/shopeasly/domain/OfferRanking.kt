package it.lagioiaproductions.shopeasly.domain

import it.lagioiaproductions.shopeasly.data.model.Offer

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
)

object OfferRanking {
    fun apply(offers: List<Offer>, filters: SearchFilters): List<Offer> {
        val filtered = offers.filter { offer ->
            offer.distanceMeters <= filters.maximumDistanceMeters &&
                (!filters.sustainableOnly || offer.sustainabilityLabels.isNotEmpty()) &&
                (filters.includeLoyaltyOffers || !offer.loyaltyRequired)
        }

        return when (filters.sortMode) {
            SortMode.PRICE -> filtered.sortedBy { it.unitPriceOrPrice() }
            SortMode.DISTANCE -> filtered.sortedBy(Offer::distanceMeters)
            SortMode.QUALITY -> filtered.sortedByDescending { it.qualityScore ?: 0f }
            SortMode.SMART -> smartSort(filtered)
        }
    }

    private fun smartSort(offers: List<Offer>): List<Offer> {
        if (offers.isEmpty()) return emptyList()

        val maximumPrice = offers.maxOf { it.unitPriceOrPrice() }.coerceAtLeast(0.01)
        val maximumDistance = offers.maxOf(Offer::distanceMeters).coerceAtLeast(1)

        return offers.sortedByDescending { offer ->
            val priceScore = 1.0 - offer.unitPriceOrPrice() / maximumPrice
            val distanceScore = 1.0 - offer.distanceMeters.toDouble() / maximumDistance
            val qualityScore = (offer.qualityScore?.toDouble() ?: 2.5) / 5.0
            val sustainabilityScore = if (offer.sustainabilityLabels.isEmpty()) 0.0 else 1.0
            val loyaltyPenalty = if (offer.loyaltyRequired) 0.08 else 0.0

            0.45 * priceScore +
                0.20 * distanceScore +
                0.20 * qualityScore +
                0.15 * sustainabilityScore -
                loyaltyPenalty
        }
    }

    private fun Offer.unitPriceOrPrice(): Double = unitPrice ?: price
}
