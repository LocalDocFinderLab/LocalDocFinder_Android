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
        SearchHistoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun searchHistoryDao(): SearchHistoryDao

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

        private fun createRoomBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_1_3, MIGRATION_3_4)
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
