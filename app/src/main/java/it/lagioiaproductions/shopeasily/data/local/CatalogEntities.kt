package it.lagioiaproductions.shopeasily.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceState { PENDING, UPDATED, NO_OFFERS, UNAVAILABLE, BLOCKED, PARSE_ERROR }

@Entity(tableName = "stores", indices = [Index("name"), Index("latitude", "longitude")])
data class StoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val category: String,
    val website: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val sustainable: Boolean,
    val source: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "offers",
    indices = [Index("storeId"), Index("productName"), Index(value = ["fingerprint"], unique = true)],
)
data class OfferEntity(
    @PrimaryKey val id: Long,
    val fingerprint: String,
    val storeId: String,
    val storeName: String,
    val productName: String,
    val price: Double,
    val distanceMeters: Int,
    val productImageUrl: String?,
    val storeWebsite: String?,
    val sourceUrl: String,
    val parserId: String,
    val confidence: Double,
    val observedAt: Long,
    val expiresAt: Long,
)

@Entity(tableName = "source_status")
data class SourceStatusEntity(
    @PrimaryKey val storeId: String,
    val storeName: String,
    val state: SourceState,
    val sourceUrl: String?,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long?,
    val offerCount: Int,
    val detail: String?,
)

