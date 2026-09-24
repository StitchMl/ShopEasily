package it.lagioiaproductions.shopeasily.data.model

enum class ProductImageKey {
    MILK,
    PASTA,
    PRODUCE,
}

enum class OfferDay(val shortLabel: String) {
    MONDAY("Lun"),
    TUESDAY("Mar"),
    WEDNESDAY("Mer"),
    THURSDAY("Gio"),
    FRIDAY("Ven"),
    SATURDAY("Sab"),
    SUNDAY("Dom"),
}

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
    val imageKey: ProductImageKey = ProductImageKey.PRODUCE,
    val activeDays: Set<OfferDay> = OfferDay.entries.toSet(),
    val minimumAge: Int? = null,
    val flashOffer: Boolean = false,
)

fun Offer.isEligibleFor(age: Int?): Boolean = minimumAge == null || (age != null && age >= minimumAge)

fun Offer.isActiveOn(day: OfferDay): Boolean = day in activeDays
