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
    version = 9,
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
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offers ADD COLUMN productImageVerified INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stores ADD COLUMN sustainabilityScore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stores ADD COLUMN sustainabilityReasons TEXT")
                db.execSQL("ALTER TABLE stores ADD COLUMN osmType TEXT")
                db.execSQL("ALTER TABLE stores ADD COLUMN osmId INTEGER")
                db.execSQL("ALTER TABLE offers ADD COLUMN labels TEXT")
                // Old rows only had a boolean: keep them visible until the next background sync.
                db.execSQL("UPDATE stores SET sustainabilityScore = 45 WHERE sustainable = 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stores ADD COLUMN brand TEXT")
                db.execSQL("ALTER TABLE stores ADD COLUMN brandWikidata TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stores ADD COLUMN place TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Earlier versions stored brands guessed from names ("Roma", "Iper"):
                // drop them, the next sync stores only brands declared in OpenStreetMap.
                db.execSQL("UPDATE stores SET brand = NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Offers read by earlier, less strict parsers (e.g. "Privacy policy 50 €", or
                // products of namesake shops elsewhere) are dropped; the next sync re-reads
                // every store with the new rules. Crowdsourced Open Prices tags are kept.
                db.execSQL("DELETE FROM offers WHERE parserId != 'open-prices'")
                db.execSQL("UPDATE stores SET website = NULL WHERE brand IS NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offers ADD COLUMN userQuality INTEGER")
                // Places that are not shops (a social network like "Meta", restaurants or
                // offices returned by generic Google types) are dropped; Google places are
                // rediscovered with the stricter food-shop filter on the next sync.
                val notShops = "SELECT id FROM stores WHERE osmId IS NULL OR normalizedName IN " +
                    "('meta','meta platforms','facebook','instagram','whatsapp','google','tiktok','telegram','youtube'," +
                    "'tripadvisor','glovo','deliveroo','just eat','uber eats','amazon','linktree')"
                db.execSQL("DELETE FROM offers WHERE parserId != 'user' AND storeId IN ($notShops)")
                db.execSQL("DELETE FROM source_status WHERE storeId IN ($notShops)")
                db.execSQL("DELETE FROM stores WHERE id IN ($notShops) AND id NOT IN (SELECT storeId FROM offers)")
            }
        }

        @Volatile private var instance: ShopEasilyDatabase? = null

        fun get(context: Context): ShopEasilyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShopEasilyDatabase::class.java,
                "shopeasily.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build().also { instance = it }
        }
    }
}
