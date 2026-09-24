package it.lagioiaproductions.shopeasily.data.repository

import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import it.lagioiaproductions.shopeasily.data.model.OfferDay
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
            imageKey = ProductImageKey.MILK,
            activeDays = setOf(OfferDay.MONDAY, OfferDay.TUESDAY, OfferDay.WEDNESDAY),
            flashOffer = true,
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
            imageKey = ProductImageKey.MILK,
            activeDays = setOf(OfferDay.THURSDAY, OfferDay.FRIDAY, OfferDay.SATURDAY),
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
            imageKey = ProductImageKey.MILK,
            minimumAge = 65,
            activeDays = setOf(OfferDay.MONDAY),
        ),
        Offer(
            id = 4,
            productName = "Pasta di semola 500 g",
            brand = "Grano Italiano",
            storeName = "Supermercato Centro",
            price = 0.89,
            unitPrice = 1.78,
            distanceMeters = 850,
            qualityScore = 4.1f,
            sustainabilityLabels = listOf("Filiera italiana"),
            validUntil = "05/10/2026",
            imageKey = ProductImageKey.PASTA,
            activeDays = setOf(OfferDay.FRIDAY, OfferDay.SATURDAY),
            flashOffer = true,
        ),
        Offer(
            id = 5,
            productName = "Cassetta ortofrutta stagionale",
            brand = "Orto Locale",
            storeName = "Mercato Verde",
            price = 6.90,
            unitPrice = null,
            distanceMeters = 1_100,
            qualityScore = 4.8f,
            sustainabilityLabels = listOf("Km 0", "Stagionale"),
            validUntil = "06/10/2026",
            imageKey = ProductImageKey.PRODUCE,
            activeDays = setOf(OfferDay.SATURDAY, OfferDay.SUNDAY),
        ),
        Offer(
            id = 6,
            productName = "Pollo allevato all'aperto 1 kg",
            brand = null,
            storeName = "Macelleria di quartiere",
            price = 8.90,
            unitPrice = 8.90,
            distanceMeters = 650,
            qualityScore = 4.6f,
            sustainabilityLabels = listOf("Allevato all'aperto", "Negozio locale"),
            validUntil = null,
            imageKey = ProductImageKey.MEAT,
        ),
        Offer(
            id = 7,
            productName = "Pane artigianale 1 kg",
            brand = null,
            storeName = "Mercato Verde",
            price = 3.20,
            unitPrice = 3.20,
            distanceMeters = 1_100,
            qualityScore = 4.7f,
            sustainabilityLabels = listOf("Artigianale", "Locale"),
            validUntil = null,
            imageKey = ProductImageKey.BAKERY,
            activeDays = setOf(OfferDay.SATURDAY, OfferDay.SUNDAY),
        ),
    ) + everydayOffers()

    private fun everydayOffers(): List<Offer> {
        val products = listOf(
            Triple("Uova da allevamento all'aperto 6 pz", 2.49, ProductImageKey.PRODUCE),
            Triple("Mele italiane 1 kg", 1.79, ProductImageKey.PRODUCE),
            Triple("Pomodori locali 1 kg", 2.19, ProductImageKey.PRODUCE),
            Triple("Riso italiano 1 kg", 2.39, ProductImageKey.PASTA),
            Triple("Yogurt bianco 4 pz", 1.69, ProductImageKey.MILK),
            Triple("Olio extravergine 1 L", 7.49, ProductImageKey.OTHER),
            Triple("Caffè macinato 250 g", 3.29, ProductImageKey.OTHER),
            Triple("Acqua minerale 6x1,5 L", 2.10, ProductImageKey.OTHER),
            Triple("Formaggio stagionato 300 g", 4.90, ProductImageKey.MILK),
            Triple("Legumi biologici 400 g", 1.15, ProductImageKey.PRODUCE),
            Triple("Pesce fresco 1 kg", 12.90, ProductImageKey.OTHER),
            Triple("Detersivo ecologico 1 L", 3.60, ProductImageKey.HOUSEHOLD),
        )
        return products.mapIndexed { index, (name, price, image) ->
            Offer(
                id = (100 + index).toLong(), productName = name, brand = null,
                storeName = if (index % 2 == 0) "Supermercato Centro" else "Market Bio",
                price = price, unitPrice = null,
                distanceMeters = if (index % 2 == 0) 850 else 1_400,
                qualityScore = 4.0f + (index % 5) / 10f,
                sustainabilityLabels = if (index % 2 == 0) listOf("Filiera italiana") else listOf("Biologico"),
                validUntil = null, imageKey = image,
            )
        }
    }

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

        return flowOf(result)
    }
}
