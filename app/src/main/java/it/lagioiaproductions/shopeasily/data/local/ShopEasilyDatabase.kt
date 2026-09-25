package it.lagioiaproductions.shopeasily.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class CatalogConverters {
    @TypeConverter fun sourceState(value: String): SourceState = SourceState.valueOf(value)
    @TypeConverter fun sourceState(value: SourceState): String = value.name
}

@Database(
    entities = [StoreEntity::class, OfferEntity::class, SourceStatusEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(CatalogConverters::class)
abstract class ShopEasilyDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offers ADD COLUMN promotional INTEGER NOT NULL DEFAULT 1")
            }
        }

        @Volatile private var instance: ShopEasilyDatabase? = null

        fun get(context: Context): ShopEasilyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShopEasilyDatabase::class.java,
                "shopeasily.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
