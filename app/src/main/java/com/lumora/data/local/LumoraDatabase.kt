package com.lumora.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumora.data.local.dao.*
import com.lumora.data.local.entity.*

@Database(
    version = 4,
    exportSchema = true,
    entities = [
        ProviderEntity::class,
        RecordingEntity::class,
        RecordingStorageEntity::class,
        EpgSourceEntity::class,
        WatchHistoryEntity::class,
        CustomGroupEntity::class,
        CustomGroupMemberEntity::class,
        EpgProgramEntity::class
    ]
)
abstract class LumoraDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun recordingDao(): RecordingDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun customGroupDao(): CustomGroupDao
    abstract fun epgProgramDao(): EpgProgramDao

    companion object {
        @Volatile private var instance: LumoraDatabase? = null

        /** v2 adds the persisted guide (epg_programs). Written as a real migration, not a
         *  destructive fallback: this database also holds recordings, watch history and
         *  custom groups, none of which can be re-derived from the providers. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_programs` (" +
                        "`channelId` TEXT NOT NULL, " +
                        "`startTimestamp` INTEGER NOT NULL, " +
                        "`stopTimestamp` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`, `startTimestamp`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_channelId` ON `epg_programs` (`channelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_stopTimestamp` ON `epg_programs` (`stopTimestamp`)")
            }
        }

        /** v3 drops `channels`, `categories` and `downloads`. All three were write-only tables
         *  filled by the catalog sync worker, which nothing ever scheduled - the browsable
         *  catalogue lives in [com.lumora.cache.ChannelCache] and downloads in
         *  `download/DownloadStore`, so no reader ever went near them. Dropping loses nothing
         *  because nothing was ever written. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `channels`")
                db.execSQL("DROP TABLE IF EXISTS `categories`")
                db.execSQL("DROP TABLE IF EXISTS `downloads`")
            }
        }

        /** v4 adds `consecutiveFailures` to `epg_sources`, so the EPG worker can skip a
         *  permanently-broken source instead of retrying it (and every other source) forever. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `epg_sources` ADD COLUMN `consecutiveFailures` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): LumoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LumoraDatabase::class.java,
                    "lumora.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
