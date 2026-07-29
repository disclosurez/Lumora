package com.lumora.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lumora.data.local.dao.*
import com.lumora.data.local.entity.*

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        ProviderEntity::class,
        ChannelEntity::class,
        CategoryEntity::class,
        RecordingEntity::class,
        RecordingStorageEntity::class,
        DownloadEntity::class,
        EpgSourceEntity::class,
        WatchHistoryEntity::class,
        CustomGroupEntity::class,
        CustomGroupMemberEntity::class
    ]
)
abstract class LumoraDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun recordingDao(): RecordingDao
    abstract fun downloadDao(): DownloadDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun customGroupDao(): CustomGroupDao

    companion object {
        @Volatile private var instance: LumoraDatabase? = null

        fun getInstance(context: Context): LumoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LumoraDatabase::class.java,
                    "lumora.db"
                )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
