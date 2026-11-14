package com.cosmicoppai.lisa.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.DownloadStatus
import com.cosmicoppai.lisa.model.MediaType

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val animeId: Int,
    val jpName: String,
    val noOfEpisodes: String,
    val type: String,
    val status: String,
    val season: String,
    val year: String,
    val score: Double,
    val poster: String,
    val session: String,
    val createdOn: Long = System.currentTimeMillis()
)

@Entity(tableName = "readlist")
data class ReadlistEntity(
    @PrimaryKey val mangaId: String,
    val title: String,
    val totalChps: String,
    val status: String,
    val genresJson: String,
    val poster: String,
    val session: String,
    val createdOn: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "download_tasks",
    indices = [Index(value = ["fileName", "seriesName"], unique = true)]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaType: MediaType,
    val seriesName: String,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedSegments: Int = 0,
    val totalSegments: Int = 0,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val sourceUrl: String,
    val manifestUrl: String = "",
    val extraHeaderReferer: String = "",
    val outputFormat: DownloadFormat,
    val fileLocation: String? = null,
    val posterUrl: String = "",
    val errorMessage: String? = null,
    val createdOn: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "download_segments",
    primaryKeys = ["taskId", "segmentIndex"]
)
data class DownloadSegmentEntity(
    val taskId: Long,
    val segmentIndex: Int
)

@Entity(tableName = "site_state")
data class SiteStateEntity(
    @PrimaryKey val siteName: String,
    val cookiesJson: String,
    val userAgent: String,
    val createdOnEpochMs: Long
)
