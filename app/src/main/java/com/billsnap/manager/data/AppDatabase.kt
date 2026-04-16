package com.billsnap.manager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database singleton.
 * Thread-safe double-checked locking pattern.
 * Version 3: Added customers table and bill extensions (customer_id, reminder, paid_timestamp).
 * Version 5: Added drive_file_id column for Google Drive image storage.
 * Version 6: Added shop_id and created_by columns for multi-user shop access.
 * Version 7: Added OCR columns for PaddleOCR integration.
 * Version 8: Added ocr_image_path for bounding-box overlay image.
 * Version 9: Added ocr_text_file_path for plain-text OCR file.
 * Version 10: Added ocr_corrections table, and smart processing fields to bills.
 * Version 11: Added partial payment tracking (paid_amount, remaining_amount, last_payment_date).
 * Version 12: Added indices for timestamp and payment_status to optimize financial analytics queries.
 * Version 13: Added payment_history_json to bills for individual payment attempts tracking.
 * Version 14: Added optimized_image_path for image optimization engine.
 * Version 15: Added worker_access table for Admin→Worker bill sync access mapping.
 */
@Database(
    entities = [BillEntity::class, CustomerEntity::class, OcrCorrectionEntity::class, WorkerAccessEntity::class],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao
    abstract fun customerDao(): CustomerDao
    abstract fun ocrCorrectionDao(): OcrCorrectionDao
    abstract fun workerAccessDao(): WorkerAccessDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from v1 → v3:
         * - Creates customers table
         * - Adds customer_id, reminder_datetime, paid_timestamp to bills
         */
        private val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create customers table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS customers (
                        customer_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        phone_number TEXT NOT NULL DEFAULT '',
                        details TEXT NOT NULL DEFAULT '',
                        profile_image_path TEXT NOT NULL DEFAULT '',
                        created_timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // Rebuild bills table to add foreign key + columns without DEFAULT
                // Step 1: Rename old table
                db.execSQL("ALTER TABLE bills RENAME TO bills_old")

                // Step 2: Create new table with proper schema (FK, no DEFAULT on new cols)
                db.execSQL("""
                    CREATE TABLE bills (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        custom_name TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        image_path TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        payment_status TEXT NOT NULL,
                        customer_id INTEGER,
                        reminder_datetime INTEGER,
                        paid_timestamp INTEGER,
                        FOREIGN KEY (customer_id) REFERENCES customers(customer_id) ON DELETE SET NULL
                    )
                """)

                // Step 3: Copy data from old table
                db.execSQL("""
                    INSERT INTO bills (id, custom_name, notes, image_path, timestamp, payment_status)
                    SELECT id, custom_name, notes, image_path, timestamp, payment_status
                    FROM bills_old
                """)

                // Step 4: Drop old table
                db.execSQL("DROP TABLE bills_old")

                // Step 5: Recreate indices
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_custom_name ON bills(custom_name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_customer_id ON bills(customer_id)")
            }
        }

        /**
         * Migration from v3 → v4:
         * - Adds sync_id column to bills and customers for cloud sync dedup
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN sync_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE customers ADD COLUMN sync_id TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v4 → v5:
         * - Adds drive_file_id column to bills and customers for Google Drive image storage
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN drive_file_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE customers ADD COLUMN drive_file_id TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v5 → v6:
         * - Adds shop_id and created_by columns to bills and customers for multi-user access
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN shop_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN created_by TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE customers ADD COLUMN shop_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE customers ADD COLUMN created_by TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v6 → v7:
         * - Adds vendor_name, total_amount, tax_amount, invoice_date, invoice_number, raw_ocr_text, ocr_confidence to bills
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN vendor_name TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN total_amount REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN tax_amount REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN invoice_date TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN invoice_number TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN raw_ocr_text TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN ocr_confidence REAL DEFAULT NULL")
            }
        }

        /**
         * Migration from v7 → v8:
         * - Adds ocr_image_path to bills to save the bounding-box overlay image
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN ocr_image_path TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v8 → v9:
         * - Adds ocr_text_file_path to bills to store the path of the saved OCR plain-text file
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN ocr_text_file_path TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v9 → v10:
         * - Adds ocr_corrections table for smart processing auto-correct memory
         * - Adds smart processing fields to bills (is_smart_processed, smart_ocr_json, late_risk_score)
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_corrections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        original_text TEXT NOT NULL,
                        corrected_text TEXT NOT NULL,
                        sync_id TEXT DEFAULT NULL,
                        shop_id TEXT DEFAULT NULL,
                        created_by TEXT DEFAULT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ocr_corrections_original_text ON ocr_corrections(original_text)")
                
                db.execSQL("ALTER TABLE bills ADD COLUMN is_smart_processed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN smart_ocr_json TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN late_risk_score REAL DEFAULT NULL")
            }
        }

        /**
         * Migration from v10 → v11:
         * - Adds paid_amount, remaining_amount, last_payment_date to bills for partial payment tracking
         * - Backfills remaining_amount = total_amount for existing bills
         * - Adds index on remaining_amount for fast profile aggregation queries
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN paid_amount REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN remaining_amount REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN last_payment_date INTEGER DEFAULT NULL")
                // Backfill: for existing bills, remaining = whatever their total_amount was (paid stays 0)
                db.execSQL("UPDATE bills SET remaining_amount = COALESCE(total_amount, 0)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_remaining_amount ON bills(remaining_amount)")
            }
        }

        /**
         * Migration from v11 → v12:
         * - Adds index on timestamp
         * - Adds index on payment_status
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_timestamp` ON `bills` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_payment_status` ON `bills` (`payment_status`)")
            }
        }

        /**
         * Migration from v12 → v13:
         * - Adds payment_history_json to `bills` column wrapper to list individual transaction fractions.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN payment_history_json TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v13 → v14:
         * - Adds optimized_image_path to store WebP compressed and perspective corrected images.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN optimized_image_path TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v14 → v15:
         * - Creates worker_access table for Admin→Worker bill sync access mapping
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS worker_access (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        admin_id TEXT NOT NULL,
                        worker_id TEXT NOT NULL,
                        shop_id TEXT NOT NULL,
                        access_status INTEGER NOT NULL DEFAULT 0,
                        last_sync_timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_worker_access_admin_id_worker_id ON worker_access(admin_id, worker_id)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "billsnap_database"
                )
                    .addMigrations(MIGRATION_1_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
