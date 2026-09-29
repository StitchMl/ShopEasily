package it.lagioiaproductions.shopeasily.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM offers WHERE expiresAt > :now ORDER BY price")
    suspend fun activeOffers(now: Long): List<OfferEntity>

    @Query("SELECT * FROM stores ORDER BY distanceMeters")
    suspend fun stores(): List<StoreEntity>

    @Query("SELECT * FROM offers WHERE id IN (:ids)")
    suspend fun offersByIds(ids: List<Long>): List<OfferEntity>

    /** Emits whenever the offers table changes: used to refresh the UI silently. */
    @Query("SELECT COUNT(*) + COALESCE(MAX(observedAt), 0) FROM offers")
    fun observeCatalogVersion(): Flow<Long>

    @Query("SELECT * FROM source_status WHERE storeId = :storeId")
    suspend fun statusFor(storeId: String): SourceStatusEntity?

    @Query("SELECT * FROM source_status ORDER BY storeName")
    fun observeSourceStatuses(): Flow<List<SourceStatusEntity>>

    @Query("SELECT * FROM source_status ORDER BY storeName")
    suspend fun sourceStatuses(): List<SourceStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStores(stores: List<StoreEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOffers(offers: List<OfferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatus(status: SourceStatusEntity)

    @Query("SELECT * FROM stores WHERE id = :id")
    suspend fun store(id: String): StoreEntity?

    @Query("DELETE FROM offers WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    /** Prices the user reported (parserId "user") are never replaced by a sync. */
    @Query("DELETE FROM offers WHERE storeId = :storeId AND parserId != 'user'")
    suspend fun deleteOffersForStore(storeId: String)

    @Query("DELETE FROM offers WHERE id IN (:ids)")
    suspend fun deleteOffers(ids: List<Long>)

    /** Removes rows created by the obsolete Open Prices query whose filters were ignored by the API. */
    @Query("DELETE FROM offers WHERE parserId = 'open-prices' AND sourceUrl LIKE '%location_osm_type%'")
    suspend fun deleteLegacyOpenPricesOffers()

    @Transaction
    suspend fun replaceStoreOffers(storeId: String, offers: List<OfferEntity>) {
        deleteOffersForStore(storeId)
        if (offers.isNotEmpty()) upsertOffers(offers)
    }
}
