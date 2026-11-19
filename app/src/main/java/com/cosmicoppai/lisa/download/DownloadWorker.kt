package com.cosmicoppai.lisa.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cosmicoppai.lisa.LiSAApplication
import com.cosmicoppai.lisa.R
import com.cosmicoppai.lisa.data.local.dao.DownloadDao
import com.cosmicoppai.lisa.data.local.entity.DownloadSegmentEntity
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.remote.AnimepaheScraper
import com.cosmicoppai.lisa.data.remote.CloudflareCookieResolver
import com.cosmicoppai.lisa.data.remote.MangaKatanaScraper
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.DownloadStatus
import com.cosmicoppai.lisa.model.MediaType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val animepaheScraper: AnimepaheScraper,
    private val mangaKatanaScraper: MangaKatanaScraper,
    private val cookieResolver: CloudflareCookieResolver,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.failure()

        val task = downloadDao.getById(taskId) ?: return Result.failure()
        runCatching {
            setForeground(createForegroundInfo(task, 0, task.totalSegments.coerceAtLeast(1)))
        }
        downloadDao.updateStatus(taskId, DownloadStatus.STARTED)

        return try {
            when (task.mediaType) {
                MediaType.ANIME -> downloadAnimeEpisode(task)
                MediaType.MANGA -> downloadMangaChapter(task)
            }
            Result.success()
        } catch (e: CancellationException) {
            val latest = downloadDao.getById(taskId)
            if (latest != null && latest.status != DownloadStatus.PAUSED) {
                downloadDao.updateStatus(taskId, DownloadStatus.PAUSED)
            }
            throw e
        } catch (t: Throwable) {
            downloadDao.updateStatus(
                taskId,
                DownloadStatus.FAILED,
                errorMessage = t.message ?: "Download failed"
            )
            Result.failure()
        }
    }

    private suspend fun downloadAnimeEpisode(task: DownloadTaskEntity) = withContext(Dispatchers.IO) {
        var manifestUrl = task.manifestUrl.ifBlank {
            if (task.sourceUrl.isBlank()) throw IOException("Missing stream source URL")
            animepaheScraper.getHlsPlaylist(task.sourceUrl).manifestUrl
        }

        val initialText = try {
            fetchText(manifestUrl, AnimepaheScraper.KWIK_HEADERS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Signed Kwik manifest URLs can expire between pause/resume; refresh from sourceUrl once.
            if (task.sourceUrl.isNotBlank()) {
                manifestUrl = animepaheScraper.getHlsPlaylist(task.sourceUrl).manifestUrl
                fetchText(manifestUrl, AnimepaheScraper.KWIK_HEADERS)
            } else {
                throw t
            }
        }
        val mediaPlaylistUrl: String
        val mediaPlaylistText: String
        when (val parsed = HlsParser.parse(initialText, manifestUrl)) {
            is ParsedHlsPlaylist.Master -> {
                val best = parsed.variants.maxByOrNull { it.bandwidth }
                    ?: throw IOException("No stream variants found")
                mediaPlaylistUrl = best.uri
                mediaPlaylistText = fetchText(mediaPlaylistUrl, AnimepaheScraper.KWIK_HEADERS)
            }
            is ParsedHlsPlaylist.Media -> {
                mediaPlaylistUrl = manifestUrl
                mediaPlaylistText = initialText
            }
        }

        val mediaParsed = HlsParser.parse(mediaPlaylistText, mediaPlaylistUrl) as? ParsedHlsPlaylist.Media
            ?: throw IOException("Expected media HLS playlist")
        val segments = mediaParsed.segments
        if (segments.isEmpty()) throw IOException("Playlist has 0 segments")

        val safeSeries = sanitizePath(task.seriesName)
        val safeFile = sanitizePath(task.fileName)
        val segDir = File(applicationContext.filesDir, "segments/$safeSeries/$safeFile").apply { mkdirs() }
        val completedIndices = downloadDao.getCompletedSegments(task.id).toHashSet()
        val doneCounter = AtomicInteger(completedIndices.size)
        val lastUiUpdate = AtomicLong(0L)

        downloadDao.updateProgress(
            taskId = task.id,
            status = DownloadStatus.STARTED,
            downloadedSegments = doneCounter.get(),
            totalSegments = segments.size,
            speedBytesPerSec = 0L,
            manifestUrl = manifestUrl
        )

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val initCache = ConcurrentHashMap<String, ByteArray>()
        val semaphore = Semaphore(8)

        coroutineScope {
            segments.filter { it.index !in completedIndices }.map { segment ->
                async {
                    semaphore.withPermit {
                        val segFile = File(segDir, "seg_${segment.index.toString().padStart(5, '0')}.ts")
                        val startNs = System.nanoTime()
                        val rawBytes = fetchBytesWithRetry(segment.uri, AnimepaheScraper.KWIK_HEADERS)
                        val initBytes = segment.initSectionUri?.let { initUri ->
                            initCache[initUri] ?: fetchBytesWithRetry(initUri, AnimepaheScraper.KWIK_HEADERS).also {
                                initCache[initUri] = it
                            }
                        }

                        val payloadBytes = if (segment.keyInfo != null && segment.keyInfo.method.equals("AES-128", true)) {
                            val keyBytes = keyCache[segment.keyInfo.uri]
                                ?: fetchBytesWithRetry(segment.keyInfo.uri, AnimepaheScraper.KWIK_HEADERS).also {
                                    keyCache[segment.keyInfo.uri] = it
                                }
                            val iv = segment.keyInfo.explicitIv ?: HlsParser.sequenceIv(segment.mediaSequence)
                            Mp4Remuxer.decryptAes128(rawBytes, keyBytes, iv)
                        } else {
                            rawBytes
                        }

                        segFile.outputStream().use { out ->
                            if (initBytes != null && segment.index == 0) {
                                out.write(initBytes)
                            }
                            out.write(payloadBytes)
                        }

                        val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                        val speed = (rawBytes.size / elapsedSec).toLong()
                        downloadDao.markSegmentDone(DownloadSegmentEntity(task.id, segment.index))
                        val doneNow = doneCounter.incrementAndGet()
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate.get() > 350L || doneNow == segments.size) {
                            lastUiUpdate.set(now)
                            downloadDao.updateProgress(
                                taskId = task.id,
                                status = DownloadStatus.STARTED,
                                downloadedSegments = doneNow,
                                totalSegments = segments.size,
                                speedBytesPerSec = speed,
                                manifestUrl = manifestUrl
                            )
                            runCatching {
                                setForegroundAsync(createForegroundInfo(task, doneNow, segments.size))
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val orderedSegments = (0 until segments.size).map { idx ->
            File(segDir, "seg_${idx.toString().padStart(5, '0')}.ts")
        }
        val baseOutDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: applicationContext.filesDir
        val outDir = File(baseOutDir, "LiSA/Anime/$safeSeries").apply { mkdirs() }
        val outputMp4 = File(outDir, "$safeFile.mp4")
        val combinedTemp = File(segDir, "combined.ts")

        var remuxSucceeded = false
        val finalSize = try {
            val size = Mp4Remuxer.remuxSegmentsToMp4(orderedSegments, combinedTemp, outputMp4)
            remuxSucceeded = outputMp4.exists() && size > 0L
            size
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Delete any corrupt/partial MP4 so fallback .ts is used cleanly.
            runCatching { if (outputMp4.exists()) outputMp4.delete() }
            val fallbackTs = File(outDir, "$safeFile.ts")
            if (combinedTemp.exists()) combinedTemp.copyTo(fallbackTs, overwrite = true)
            fallbackTs.length()
        }

        val finalLocation = if (remuxSucceeded) {
            outputMp4.absolutePath
        } else {
            val fallbackTs = File(outDir, "$safeFile.ts")
            if (!fallbackTs.exists() && combinedTemp.exists()) {
                combinedTemp.copyTo(fallbackTs, overwrite = true)
            }
            fallbackTs.absolutePath
        }

        segDir.deleteRecursively()
        downloadDao.deleteSegmentsForTask(task.id)
        downloadDao.markCompleted(
            taskId = task.id,
            totalSegments = segments.size,
            totalBytes = finalSize,
            fileLocation = finalLocation
        )
    }

    private suspend fun downloadMangaChapter(task: DownloadTaskEntity) = withContext(Dispatchers.IO) {
        val chapterMedia = mangaKatanaScraper.getChapterImages(task.sourceUrl)
        val imageUrls = chapterMedia.imageUrls
        if (imageUrls.isEmpty()) throw IOException("No manga pages found for chapter")

        val safeSeries = sanitizePath(task.seriesName)
        val safeChapter = sanitizePath(task.fileName)
        val baseOutDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: applicationContext.filesDir
        val chapterDir = File(baseOutDir, "LiSA/Manga/$safeSeries/$safeChapter").apply { mkdirs() }
        val completedIndices = downloadDao.getCompletedSegments(task.id).toHashSet()
        val doneCounter = AtomicInteger(completedIndices.size)
        val totalBytes = AtomicLong(0L)
        val lastUiUpdate = AtomicLong(0L)
        val chapterReferer = task.sourceUrl.ifBlank { "${MangaKatanaScraper.SITE_URL}/" }

        downloadDao.updateProgress(
            taskId = task.id,
            status = DownloadStatus.STARTED,
            downloadedSegments = doneCounter.get(),
            totalSegments = imageUrls.size,
            speedBytesPerSec = 0L,
            manifestUrl = task.sourceUrl
        )

        val semaphore = Semaphore(6)
        coroutineScope {
            imageUrls.mapIndexedNotNull { idx, url ->
                if (idx in completedIndices) null else idx to url
            }.map { (idx, url) ->
                val candidates = (listOf(url) + chapterMedia.fallbackServers.mapNotNull { it.getOrNull(idx) }).distinct()
                async {
                    semaphore.withPermit {
                        val imgFile = File(chapterDir, "img-${idx.toString().padStart(4, '0')}.jpg")
                        val startNs = System.nanoTime()
                        var bytes: ByteArray? = null
                        var lastErr: Throwable? = null
                        for (candidate in candidates) {
                            try {
                                bytes = fetchBytesWithRetry(candidate, mapOf("Referer" to chapterReferer))
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                lastErr = t
                            }
                        }
                        val resolvedBytes = bytes ?: throw (lastErr ?: IOException("Failed downloading page ${idx + 1}"))
                        imgFile.writeBytes(resolvedBytes)
                        totalBytes.addAndGet(resolvedBytes.size.toLong())

                        val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                        val speed = (resolvedBytes.size / elapsedSec).toLong()
                        downloadDao.markSegmentDone(DownloadSegmentEntity(task.id, idx))
                        val doneNow = doneCounter.incrementAndGet()
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate.get() > 350L || doneNow == imageUrls.size) {
                            lastUiUpdate.set(now)
                            downloadDao.updateProgress(
                                taskId = task.id,
                                status = DownloadStatus.STARTED,
                                downloadedSegments = doneNow,
                                totalSegments = imageUrls.size,
                                speedBytesPerSec = speed,
                                manifestUrl = task.sourceUrl
                            )
                            runCatching {
                                setForegroundAsync(createForegroundInfo(task, doneNow, imageUrls.size))
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val orderedImages = (0 until imageUrls.size).map { idx ->
            File(chapterDir, "img-${idx.toString().padStart(4, '0')}.jpg")
        }.filter { it.exists() }

        val finalLocation: String
        val finalSize: Long
        if (task.outputFormat == DownloadFormat.PDF) {
            val pdfFile = File(chapterDir.parentFile, "$safeChapter.pdf")
            finalSize = MangaPdfGenerator.convertImagesToPdf(orderedImages, pdfFile)
            chapterDir.deleteRecursively()
            finalLocation = pdfFile.absolutePath
        } else {
            finalSize = orderedImages.sumOf { it.length() }
            finalLocation = chapterDir.absolutePath
        }

        downloadDao.deleteSegmentsForTask(task.id)
        downloadDao.markCompleted(
            taskId = task.id,
            totalSegments = imageUrls.size,
            totalBytes = finalSize,
            fileLocation = finalLocation
        )
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String {
        val bytes = fetchBytesWithRetry(url, headers)
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend fun fetchBytesWithRetry(url: String, headers: Map<String, String>): ByteArray {
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", cookieResolver.cachedUserAgent)
                headers.forEach { (k, v) -> builder.header(k, v) }
                okHttpClient.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
                    return resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 3) delay(800L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Failed downloading $url")
    }

    private fun createForegroundInfo(task: DownloadTaskEntity, progress: Int, total: Int): ForegroundInfo {
        val title = "${task.seriesName} - ${task.fileName}"
        val content = if (total > 0) "Downloading ($progress/$total)" else "Preparing download..."
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            LiSAApplication.DOWNLOAD_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.lisa_logo)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), progress, total <= 0)
            .build()

        val notifId = (task.id % Int.MAX_VALUE).toInt().coerceAtLeast(100)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"

        fun sanitizePath(raw: String): String =
            raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "item" }
    }
}
