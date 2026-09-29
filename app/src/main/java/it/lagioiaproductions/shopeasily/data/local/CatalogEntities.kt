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
    /** Leaf score 0..100 computed by StoreSustainability from public tags. */
    val sustainabilityScore: Int = 0,
    /** Evidence for the leaf score, separated by '|'. */
    val sustainabilityReasons: String? = null,
    /** OpenStreetMap element, used to query Open Prices for small shops. */
    val osmType: String? = null,
    val osmId: Long? = null,
    /** Retail brand learnt from OpenStreetMap (brand tag or repeated name); null for independents. */
    val brand: String? = null,
    val brandWikidata: String? = null,
    /** City/district from OSM address tags: never mistaken for a brand. */
    val place: String? = null,
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
    val promotional: Boolean = true,
    val productImageVerified: Boolean = false,
    /** Product sustainability labels from the source, separated by '|'. */
    val labels: String? = null,
    /** Quality 1..5 given by the user for a price they reported themselves (parserId "user"). */
    val userQuality: Int? = null,
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
