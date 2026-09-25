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

    @Query("DELETE FROM offers WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM offers WHERE storeId = :storeId")
    suspend fun deleteOffersForStore(storeId: String)

    @Transaction
    suspend fun replaceStoreOffers(storeId: String, offers: List<OfferEntity>) {
        deleteOffersForStore(storeId)
        if (offers.isNotEmpty()) upsertOffers(offers)
    }
}

