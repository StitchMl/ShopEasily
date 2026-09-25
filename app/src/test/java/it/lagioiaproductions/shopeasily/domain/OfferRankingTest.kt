package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.model.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferRankingTest {
    private val offers = listOf(
        offer(id = 1, price = 2.0, distance = 500, sustainable = true),
        offer(id = 2, price = 1.0, distance = 2_000, sustainable = false),
    )

    @Test
    fun priceSortPlacesCheapestOfferFirst() {
        val result = OfferRanking.apply(offers, SearchFilters(sortMode = SortMode.PRICE))

        assertEquals(2L, result.first().id)
    }

    @Test
    fun sustainableFilterRanksLabelledFirstWithoutHidingAlternatives() {
        val result = OfferRanking.apply(offers, SearchFilters(sustainableOnly = true))

        assertEquals(listOf(1L, 2L), result.map(Offer::id))
    }

    @Test
    fun loyaltyOfferRequiresTheMatchingOwnedCard() {
        val loyaltyOffer = offer(id = 3, price = 0.5, distance = 100, sustainable = false)
            .copy(storeName = "Market", loyaltyRequired = true)

        assertTrue(OfferRanking.apply(listOf(loyaltyOffer), SearchFilters()).isEmpty())
        assertEquals(
            listOf(3L),
            OfferRanking.apply(
                listOf(loyaltyOffer),
                SearchFilters(loyaltyCards = setOf("Market")),
            ).map(Offer::id),
        )
    }

    private fun offer(id: Long, price: Double, distance: Int, sustainable: Boolean) = Offer(
        id = id,
        productName = "Prodotto $id",
        brand = null,
        storeName = "Negozio",
        price = price,
        unitPrice = price,
        distanceMeters = distance,
        qualityScore = 4f,
        sustainabilityLabels = if (sustainable) listOf("Bio") else emptyList(),
        validUntil = null,
    )
}
