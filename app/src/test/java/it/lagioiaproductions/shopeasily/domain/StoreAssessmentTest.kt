package it.lagioiaproductions.shopeasily.domain

import it.lagioiaproductions.shopeasily.data.repository.NearbyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAssessmentTest {
    @Test
    fun reviewsAreBayesianAndPriceLevelBecomesValueScore() {
        val assessment = StoreAssessmentEngine.assess(
            store(rating = 4.8, reviews = 200, priceLevel = "PRICE_LEVEL_EXPENSIVE"),
            emptyList(),
        )
        assertTrue(assessment.qualityScore!! in 90..96)
        assertEquals(30, assessment.valueScore)
        assertEquals(PriceTendency.HIGH, assessment.priceTendency)
    }

    @Test
    fun missingEvidenceDoesNotCreateScores() {
        val assessment = StoreAssessmentEngine.assess(store(), emptyList())
        assertNull(assessment.qualityScore)
        assertNull(assessment.valueScore)
    }

    private fun store(rating: Double? = null, reviews: Int = 0, priceLevel: String? = null) = NearbyStore(
        id = "market", name = "Mercato", category = "marketplace", website = null,
        latitude = 0.0, longitude = 0.0, distanceMeters = 0, sustainable = true,
        reviewRating = rating, reviewCount = reviews, priceLevel = priceLevel,
    )
}
