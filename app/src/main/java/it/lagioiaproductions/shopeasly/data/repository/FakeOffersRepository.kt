package it.lagioiaproductions.shopeasly.data.repository

import it.lagioiaproductions.shopeasly.data.model.Offer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeOffersRepository : OffersRepository {
    private val offers = listOf(
        Offer(
            id = 1,
            productName = "Latte fresco intero 1 L",
            brand = "Fattoria Verde",
            storeName = "Supermercato Centro",
            price = 1.29,
            unitPrice = 1.29,
            distanceMeters = 850,
            qualityScore = 4.3f,
            sustainabilityLabels = listOf("Locale", "Benessere animale"),
            validUntil = "30/09/2026",
        ),
        Offer(
            id = 2,
            productName = "Latte biologico 1 L",
            brand = "Bio Natura",
            storeName = "Market Bio",
            price = 1.69,
            unitPrice = 1.69,
            distanceMeters = 1_400,
            qualityScore = 4.7f,
            sustainabilityLabels = listOf("Biologico"),
            validUntil = "02/10/2026",
        ),
        Offer(
            id = 3,
            productName = "Latte UHT 1 L",
            brand = "Risparmio",
            storeName = "Discount Sud",
            price = 0.99,
            unitPrice = 0.99,
            distanceMeters = 2_600,
            qualityScore = 3.8f,
            sustainabilityLabels = emptyList(),
            validUntil = "28/09/2026",
            loyaltyRequired = true,
        ),
    )

    override fun search(query: String): Flow<List<Offer>> {
        val normalizedQuery = query.trim()
        val result = if (normalizedQuery.isEmpty()) {
            offers
        } else {
            offers.filter { offer ->
                offer.productName.contains(normalizedQuery, ignoreCase = true) ||
                    offer.brand?.contains(normalizedQuery, ignoreCase = true) == true
            }
        }

        return flowOf(result.sortedBy(Offer::price))
    }
}
