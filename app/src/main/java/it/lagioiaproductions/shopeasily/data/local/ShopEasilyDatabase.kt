package it.lagioiaproductions.shopeasily.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class CatalogConverters {
    @TypeConverter fun sourceState(value: String): SourceState = SourceState.valueOf(value)
    @TypeConverter fun sourceState(value: SourceState): String = value.name
}

@Database(
    entities = [StoreEntity::class, OfferEntity::class, SourceStatusEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(CatalogConverters::class)
abstract class ShopEasilyDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile private var instance: ShopEasilyDatabase? = null

        fun get(context: Context): ShopEasilyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShopEasilyDatabase::class.java,
                "shopeasily.db",
            ).build().also { instance = it }
        }
    }
}
