package it.lagioiaproductions.shopeasly.data.model

data class Offer(
    val id: Long,
    val productName: String,
    val brand: String?,
    val storeName: String,
    val price: Double,
    val unitPrice: Double?,
    val distanceMeters: Int,
    val qualityScore: Float?,
    val sustainabilityLabels: List<String>,
    val validUntil: String?,
    val loyaltyRequired: Boolean = false,
)
