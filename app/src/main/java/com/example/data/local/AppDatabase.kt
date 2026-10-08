package com.example.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentChunkEntity::class,
        DocumentChunkFtsEntity::class,
        DocumentTagEntity::class,
        SearchHistoryEntity::class,
        DocumentPathEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun documentPathDao(): DocumentPathDao

    /** Re-reads every row of `documents` into the FTS index (repair after external edits). */
    fun rebuildFtsIndex() {
        openHelper.writableDatabase.execSQL("INSERT INTO `documents_fts`(`documents_fts`) VALUES('rebuild')")
    }

    /** Merges FTS b-tree segments into one for faster MATCH queries; run after bulk indexing. */
    fun optimizeFtsIndex() {
        openHelper.writableDatabase.execSQL("INSERT INTO `documents_fts`(`documents_fts`) VALUES('optimize')")
    }

    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "docuvector_database.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`chunkId` INTEGER NOT NULL, `chunkText` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileUri` TEXT NOT NULL, tokenize=unicode61)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `documents` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileUri` TEXT NOT NULL, `tag` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_tags_fileUri` ON `document_tags` (`fileUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_tags_tag` ON `document_tags` (`tag`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_document_tags_fileUri_tag` ON `document_tags` (`fileUri`, `tag`)")

                // Update FTS table to include tags
                db.execSQL("DROP TABLE IF EXISTS `documents_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`chunkId` INTEGER NOT NULL, `chunkText` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileUri` TEXT NOT NULL, `tags` TEXT NOT NULL, tokenize=unicode61)"
                )
            }
        }

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `documents` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileUri` TEXT NOT NULL, `tag` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_tags_fileUri` ON `document_tags` (`fileUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_tags_tag` ON `document_tags` (`tag`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_document_tags_fileUri_tag` ON `document_tags` (`fileUri`, `tag`)")

                db.execSQL("DROP TABLE IF EXISTS `documents_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`chunkId` INTEGER NOT NULL, `chunkText` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileUri` TEXT NOT NULL, `tags` TEXT NOT NULL, tokenize=unicode61)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL, `searchMode` TEXT NOT NULL, `resultCount` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `filterTag` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_timestamp` ON `search_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_query` ON `search_history` (`query`)")
            }
        }

        /**
         * v4 -> v5: `documents_fts` becomes an external-content FTS4 table over `documents`
         * (rowid == documents.id, no duplicated text, trigger-synced by Room). The old standalone
         * table is dropped and the new index is populated from existing rows via `rebuild`.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `documents_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`chunkText` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileUri` TEXT NOT NULL, `tags` TEXT NOT NULL, tokenize=unicode61, content=`documents`)"
                )
                db.execSQL("INSERT INTO `documents_fts`(`documents_fts`) VALUES('rebuild')")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_paths` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, `path` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `isTreeUri` INTEGER NOT NULL, `status` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_document_paths_uri` ON `document_paths` (`uri`)")
            }
        }

        private fun createRoomBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_1_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val db = createRoomBuilder(context).build()
            return try {
                // Test open connection eagerly to catch any migration errors early
                db.openHelper.writableDatabase
                db
            } catch (e: Throwable) {
                Log.e(TAG, "Database initialization/migration failed (${e.message}), recreating clean database…", e)
                try {
                    context.deleteDatabase(DB_NAME)
                } catch (_: Throwable) {}
                val cleanDb = createRoomBuilder(context).build()
                try {
                    cleanDb.openHelper.writableDatabase
                } catch (_: Throwable) {}
                cleanDb
            }
        }
    }
}
