package com.cosmicoppai.lisa.download

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cosmicoppai.lisa.data.local.dao.DownloadDao
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.remote.AnimepaheScraper
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.DownloadStatus
import com.cosmicoppai.lisa.model.MangaChapter
import com.cosmicoppai.lisa.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManagerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val animepaheScraper: AnimepaheScraper
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    suspend fun enqueueAnimeEpisode(
        seriesName: String,
        episodeName: String,
        kwikUrl: String,
        manifestUrl: String = "",
        posterUrl: String = ""
    ) {
        val existing = downloadDao.findExisting(seriesName, episodeName)
        if (existing != null && existing.status == DownloadStatus.DOWNLOADED) return

        val taskId = if (existing != null) {
            downloadDao.rescheduleExisting(
                taskId = existing.id,
                sourceUrl = kwikUrl.ifBlank { existing.sourceUrl },
                manifestUrl = manifestUrl.ifBlank { existing.manifestUrl }
            )
            existing.id
        } else {
            downloadDao.insert(
                DownloadTaskEntity(
                    mediaType = MediaType.ANIME,
                    seriesName = seriesName,
                    fileName = episodeName,
                    status = DownloadStatus.SCHEDULED,
                    sourceUrl = kwikUrl,
                    manifestUrl = manifestUrl,
                    outputFormat = DownloadFormat.MP4,
                    posterUrl = posterUrl
                )
            )
        }
        enqueueWorker(taskId)
    }

    suspend fun enqueueAnimeBatch(
        seriesName: String,
        animeSession: String,
        episodes: List<AnimeEpisode>,
        preferredAudio: String = "jpn",
        preferredResolution: Int = 1080,
        posterUrl: String = ""
    ) = coroutineScope {
        val semaphore = Semaphore(3)
        episodes.map { ep ->
            async {
                semaphore.withPermit {
                    try {
                        val streams = animepaheScraper.getStreamData(animeSession, ep.epSession)
                        val options = streams[preferredAudio] ?: streams.values.firstOrNull().orEmpty()
                        val chosen = options.firstOrNull {
                            it.resolution.filter { ch -> ch.isDigit() }.toIntOrNull() == preferredResolution
                        } ?: options.maxByOrNull {
                            it.resolution.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0
                        }
                        if (chosen != null) {
                            val kwik = animepaheScraper.getHlsPlaylist(chosen.kwikUrl)
                            enqueueAnimeEpisode(
                                seriesName = seriesName,
                                episodeName = "Episode ${ep.epNo} (${chosen.resolution}p ${chosen.audio.uppercase()})",
                                kwikUrl = chosen.kwikUrl,
                                manifestUrl = kwik.manifestUrl,
                                posterUrl = posterUrl
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Isolate single-episode resolution failure so remaining episodes still enqueue
                    }
                }
            }
        }.awaitAll()
    }

    suspend fun enqueueMangaChapter(
        seriesName: String,
        chapter: MangaChapter,
        format: DownloadFormat,
        posterUrl: String = ""
    ) {
        val fileLabel = "${chapter.chpName} (${format.name})"
        val existing = downloadDao.findExisting(seriesName, fileLabel)
        if (existing != null && existing.status == DownloadStatus.DOWNLOADED) return

        val taskId = if (existing != null) {
            downloadDao.rescheduleExisting(
                taskId = existing.id,
                sourceUrl = chapter.chpSession,
                manifestUrl = ""
            )
            existing.id
        } else {
            downloadDao.insert(
                DownloadTaskEntity(
                    mediaType = MediaType.MANGA,
                    seriesName = seriesName,
                    fileName = fileLabel,
                    status = DownloadStatus.SCHEDULED,
                    sourceUrl = chapter.chpSession,
                    outputFormat = format,
                    posterUrl = posterUrl
                )
            )
        }
        enqueueWorker(taskId)
    }

    suspend fun enqueueMangaBatch(
        seriesName: String,
        chapters: List<MangaChapter>,
        format: DownloadFormat,
        posterUrl: String = ""
    ) {
        chapters.forEach { chp ->
            enqueueMangaChapter(seriesName, chp, format, posterUrl)
        }
    }

    suspend fun pause(taskId: Long) {
        downloadDao.updateStatus(taskId, DownloadStatus.PAUSED)
        workManager.cancelUniqueWork(workName(taskId))
    }

    suspend fun resume(taskId: Long) {
        downloadDao.updateStatus(taskId, DownloadStatus.SCHEDULED)
        enqueueWorker(taskId)
    }

    suspend fun cancelOrDelete(taskId: Long) {
        workManager.cancelUniqueWork(workName(taskId))
        val task = downloadDao.getById(taskId)
        task?.fileLocation?.let { path ->
            runCatching { File(path).deleteRecursively() }
        }
        if (task != null) {
            val safeSeries = DownloadWorker.sanitizePath(task.seriesName)
            val safeFile = DownloadWorker.sanitizePath(task.fileName)
            runCatching {
                File(context.filesDir, "segments/$safeSeries/$safeFile").deleteRecursively()
            }
            if (task.mediaType == MediaType.MANGA) {
                val basePicDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                runCatching {
                    File(basePicDir, "LiSA/Manga/$safeSeries/$safeFile").deleteRecursively()
                }
            }
        }
        downloadDao.deleteSegmentsForTask(taskId)
        downloadDao.deleteTask(taskId)
    }

    private fun enqueueWorker(taskId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
            .build()

        workManager.enqueueUniqueWork(
            workName(taskId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun workName(taskId: Long) = "lisa_download_$taskId"
}
