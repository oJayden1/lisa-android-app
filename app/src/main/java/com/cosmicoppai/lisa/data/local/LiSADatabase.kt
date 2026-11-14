package com.cosmicoppai.lisa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.cosmicoppai.lisa.data.local.dao.DownloadDao
import com.cosmicoppai.lisa.data.local.dao.ReadlistDao
import com.cosmicoppai.lisa.data.local.dao.SiteStateDao
import com.cosmicoppai.lisa.data.local.dao.WatchlistDao
import com.cosmicoppai.lisa.data.local.entity.DownloadSegmentEntity
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.local.entity.ReadlistEntity
import com.cosmicoppai.lisa.data.local.entity.SiteStateEntity
import com.cosmicoppai.lisa.data.local.entity.WatchlistEntity
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.DownloadStatus
import com.cosmicoppai.lisa.model.MediaType

class LiSATypeConverters {
    @TypeConverter fun fromMediaType(value: MediaType): String = value.name
    @TypeConverter fun toMediaType(value: String): MediaType = MediaType.valueOf(value)

    @TypeConverter fun fromDownloadStatus(value: DownloadStatus): String = value.name
    @TypeConverter fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)

    @TypeConverter fun fromDownloadFormat(value: DownloadFormat): String = value.name
    @TypeConverter fun toDownloadFormat(value: String): DownloadFormat = DownloadFormat.valueOf(value)
}

@Database(
    entities = [
        WatchlistEntity::class,
        ReadlistEntity::class,
        DownloadTaskEntity::class,
        DownloadSegmentEntity::class,
        SiteStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(LiSATypeConverters::class)
abstract class LiSADatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun readlistDao(): ReadlistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun siteStateDao(): SiteStateDao
}
