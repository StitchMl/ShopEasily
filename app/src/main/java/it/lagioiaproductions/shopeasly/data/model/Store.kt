package it.lagioiaproductions.shopeasly.data.model

enum class StoreChannel {
    PHYSICAL,
    ONLINE,
}

data class Store(
    val id: Long,
    val name: String,
    val channel: StoreChannel,
    val latitude: Double?,
    val longitude: Double?,
    val distanceMeters: Int,
    val deliveryFee: Double = 0.0,
    val minimumOrder: Double = 0.0,
    val deliveryEmissionKgCo2: Double = 0.0,
    val laborScore: Double? = null,
    val laborScoreSource: String? = null,
)

data class CatalogPrice(
    val store: Store,
    val productName: String,
    val aliases: Set<String>,
    val price: Double,
    val promotional: Boolean,
)
