package com.cosmicoppai.lisa.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cosmicoppai.lisa.data.local.entity.DownloadSegmentEntity
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.local.entity.ReadlistEntity
import com.cosmicoppai.lisa.data.local.entity.SiteStateEntity
import com.cosmicoppai.lisa.data.local.entity.WatchlistEntity
import com.cosmicoppai.lisa.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY createdOn DESC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE animeId = :animeId)")
    fun observeIsSaved(animeId: Int): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE animeId = :animeId)")
    suspend fun isSaved(animeId: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE animeId = :animeId")
    suspend fun delete(animeId: Int)
}

@Dao
interface ReadlistDao {
    @Query("SELECT * FROM readlist ORDER BY createdOn DESC")
    fun observeAll(): Flow<List<ReadlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM readlist WHERE mangaId = :mangaId)")
    fun observeIsSaved(mangaId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM readlist WHERE mangaId = :mangaId)")
    suspend fun isSaved(mangaId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReadlistEntity)

    @Query("DELETE FROM readlist WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks WHERE status != :completed ORDER BY createdOn DESC")
    fun observeActive(completed: DownloadStatus = DownloadStatus.DOWNLOADED): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = :completed ORDER BY createdOn DESC")
    fun observeCompleted(completed: DownloadStatus = DownloadStatus.DOWNLOADED): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :taskId")
    suspend fun getById(taskId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE seriesName = :seriesName AND fileName = :fileName LIMIT 1")
    suspend fun findExisting(seriesName: String, fileName: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Query(
        """
        UPDATE download_tasks
        SET status = :status, errorMessage = :errorMessage
        WHERE id = :taskId
        """
    )
    suspend fun updateStatus(taskId: Long, status: DownloadStatus, errorMessage: String? = null)

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            sourceUrl = :sourceUrl,
            manifestUrl = :manifestUrl,
            errorMessage = NULL
        WHERE id = :taskId
        """
    )
    suspend fun rescheduleExisting(
        taskId: Long,
        sourceUrl: String,
        manifestUrl: String,
        status: DownloadStatus = DownloadStatus.SCHEDULED
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            downloadedSegments = :downloadedSegments,
            totalSegments = :totalSegments,
            speedBytesPerSec = :speedBytesPerSec,
            manifestUrl = :manifestUrl
        WHERE id = :taskId
        """
    )
    suspend fun updateProgress(
        taskId: Long,
        status: DownloadStatus,
        downloadedSegments: Int,
        totalSegments: Int,
        speedBytesPerSec: Long,
        manifestUrl: String
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            downloadedSegments = :totalSegments,
            totalSegments = :totalSegments,
            totalBytes = :totalBytes,
            speedBytesPerSec = 0,
            fileLocation = :fileLocation,
            errorMessage = NULL
        WHERE id = :taskId
        """
    )
    suspend fun markCompleted(
        taskId: Long,
        totalSegments: Int,
        totalBytes: Long,
        fileLocation: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADED
    )

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSegmentDone(segment: DownloadSegmentEntity)

    @Query("SELECT segmentIndex FROM download_segments WHERE taskId = :taskId")
    suspend fun getCompletedSegments(taskId: Long): List<Int>

    @Query("DELETE FROM download_segments WHERE taskId = :taskId")
    suspend fun deleteSegmentsForTask(taskId: Long)
}

@Dao
interface SiteStateDao {
    @Query("SELECT * FROM site_state WHERE siteName = :siteName LIMIT 1")
    suspend fun getState(siteName: String): SiteStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SiteStateEntity)

    @Query("DELETE FROM site_state WHERE siteName = :siteName")
    suspend fun delete(siteName: String)

    @Query("DELETE FROM site_state")
    suspend fun clearAll()
}
