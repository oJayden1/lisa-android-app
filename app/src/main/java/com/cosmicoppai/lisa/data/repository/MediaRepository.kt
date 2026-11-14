package com.cosmicoppai.lisa.data.repository

import com.cosmicoppai.lisa.data.local.LisaSettings
import com.cosmicoppai.lisa.data.local.UserPreferences
import com.cosmicoppai.lisa.data.local.dao.DownloadDao
import com.cosmicoppai.lisa.data.local.dao.ReadlistDao
import com.cosmicoppai.lisa.data.local.dao.WatchlistDao
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.local.entity.ReadlistEntity
import com.cosmicoppai.lisa.data.local.entity.WatchlistEntity
import com.cosmicoppai.lisa.data.remote.AnimepaheScraper
import com.cosmicoppai.lisa.data.remote.CloudflareCookieResolver
import com.cosmicoppai.lisa.data.remote.CookieChallengeRequest
import com.cosmicoppai.lisa.data.remote.MalScraper
import com.cosmicoppai.lisa.data.remote.MangaChapterMedia
import com.cosmicoppai.lisa.data.remote.MangaKatanaScraper
import com.cosmicoppai.lisa.data.remote.MangaSearchPage
import com.cosmicoppai.lisa.download.DownloadManagerController
import com.cosmicoppai.lisa.media.MasterManifestBuilder
import com.cosmicoppai.lisa.media.PreparedAnimeStream
import com.cosmicoppai.lisa.model.AnimeDescription
import com.cosmicoppai.lisa.model.AnimeDetailsPage
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.AnimeExploreCategory
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.AppMode
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.ExplorePage
import com.cosmicoppai.lisa.model.MangaChapter
import com.cosmicoppai.lisa.model.MangaDetailsResult
import com.cosmicoppai.lisa.model.MangaExploreCategory
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.model.StreamOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val animepaheScraper: AnimepaheScraper,
    private val mangaKatanaScraper: MangaKatanaScraper,
    private val malScraper: MalScraper,
    private val watchlistDao: WatchlistDao,
    private val readlistDao: ReadlistDao,
    private val downloadDao: DownloadDao,
    private val masterManifestBuilder: MasterManifestBuilder,
    private val downloadController: DownloadManagerController,
    private val cookieResolver: CloudflareCookieResolver,
    private val userPreferences: UserPreferences,
    private val json: Json
) {
    val settings: Flow<LisaSettings> = userPreferences.settings
    val interactiveChallenge: StateFlow<CookieChallengeRequest?> = cookieResolver.interactiveChallenge

    suspend fun setAppMode(mode: AppMode) = userPreferences.setAppMode(mode)
    suspend fun setPreferredAudio(audio: String) = userPreferences.setPreferredAudio(audio)
    suspend fun setPreferredQuality(quality: Int) = userPreferences.setPreferredQuality(quality)
    suspend fun setPreferredMangaFormat(format: DownloadFormat) = userPreferences.setPreferredMangaFormat(format)
    suspend fun setAnimepaheBaseUrl(url: String) = userPreferences.setAnimepaheBaseUrl(url)

    suspend fun refreshCookies(baseUrl: String) {
        cookieResolver.ensureSession(CloudflareCookieResolver.ANIMEPAHE_SITE, baseUrl, forceRefresh = true)
    }

    suspend fun persistInteractiveChallenge(siteName: String, siteUrl: String) {
        cookieResolver.persistInteractiveResult(siteName, siteUrl)
    }

    suspend fun clearCookies() = cookieResolver.clearCookies()

    // Search & Explore
    suspend fun searchAnime(query: String): List<AnimeSearchItem> = animepaheScraper.searchAnime(query)
    suspend fun searchManga(query: String, page: Int = 1): MangaSearchPage =
        mangaKatanaScraper.searchManga(query, page)

    suspend fun getTopAnime(category: AnimeExploreCategory, offset: Int): ExplorePage =
        malScraper.getTopAnime(category, offset)

    suspend fun getTopManga(category: MangaExploreCategory, offset: Int): ExplorePage =
        malScraper.getTopManga(category, offset)

    suspend fun resolveExploreAnime(title: String): AnimeSearchItem? =
        animepaheScraper.searchAnime(title, limit = 1).firstOrNull()

    suspend fun resolveExploreManga(title: String): MangaSearchItem? =
        mangaKatanaScraper.searchManga(title, page = 1, limit = 1).results.firstOrNull()

    // Anime Details & Streaming
    suspend fun resolveSessionById(animeId: Int): String =
        animepaheScraper.resolveSessionByAnimeId(animeId)

    suspend fun getAnimeDetails(animeSession: String, page: Int = 1): AnimeDetailsPage =
        animepaheScraper.getEpisodeDetails(animeSession, page) { animeId ->
            watchlistDao.isSaved(animeId)
        }

    suspend fun getAnimeRecommendations(animeSession: String): List<AnimeSearchItem> =
        animepaheScraper.getRecommendations(animeSession)

    suspend fun getStreamOptions(animeSession: String, epSession: String): Map<String, List<StreamOption>> =
        animepaheScraper.getStreamData(animeSession, epSession)

    suspend fun prepareAnimeStream(
        options: List<StreamOption>,
        audio: String,
        cacheKey: String
    ): PreparedAnimeStream = masterManifestBuilder.prepareStream(options, audio, cacheKey)

    // Manga Details & Reading
    suspend fun getMangaDetails(mangaSession: String): MangaDetailsResult =
        mangaKatanaScraper.getMangaDetail(mangaSession) { mangaId ->
            readlistDao.isSaved(mangaId)
        }

    suspend fun getChapterImages(chpUrl: String): MangaChapterMedia =
        mangaKatanaScraper.getChapterImages(chpUrl)

    // Watchlist & Readlist
    fun observeWatchlist(): Flow<List<WatchlistEntity>> = watchlistDao.observeAll()
    fun observeReadlist(): Flow<List<ReadlistEntity>> = readlistDao.observeAll()
    fun observeIsAnimeSaved(animeId: Int): Flow<Boolean> = watchlistDao.observeIsSaved(animeId)
    fun observeIsMangaSaved(mangaId: String): Flow<Boolean> = readlistDao.observeIsSaved(mangaId)

    suspend fun toggleWatchlist(item: AnimeSearchItem, description: AnimeDescription?): Boolean {
        val animeId = description?.animeId ?: item.animeId ?: return false
        if (animeId <= 0) return false
        val currentlySaved = watchlistDao.isSaved(animeId)
        if (currentlySaved) {
            watchlistDao.delete(animeId)
            return false
        }
        watchlistDao.upsert(
            WatchlistEntity(
                animeId = animeId,
                jpName = item.title,
                noOfEpisodes = item.noOfEpisodes,
                type = description?.type ?: item.type,
                status = description?.status ?: item.status,
                season = description?.season ?: item.season,
                year = description?.year ?: item.year,
                score = item.score,
                poster = item.poster,
                session = item.session
            )
        )
        return true
    }

    suspend fun toggleReadlist(item: MangaSearchItem, mangaId: String): Boolean {
        if (mangaId.isBlank()) return false
        val currentlySaved = readlistDao.isSaved(mangaId)
        if (currentlySaved) {
            readlistDao.delete(mangaId)
            return false
        }
        readlistDao.upsert(
            ReadlistEntity(
                mangaId = mangaId,
                title = item.title,
                totalChps = item.totalChps,
                status = item.status,
                genresJson = json.encodeToString(item.genres),
                poster = item.poster,
                session = item.session
            )
        )
        return true
    }

    // Downloads
    fun observeActiveDownloads(): Flow<List<DownloadTaskEntity>> = downloadDao.observeActive()
    fun observeCompletedDownloads(): Flow<List<DownloadTaskEntity>> = downloadDao.observeCompleted()
    suspend fun getDownloadById(taskId: Long): DownloadTaskEntity? = downloadDao.getById(taskId)

    suspend fun downloadAnimeEpisode(
        seriesName: String,
        episodeName: String,
        kwikUrl: String,
        manifestUrl: String = "",
        posterUrl: String = ""
    ) = downloadController.enqueueAnimeEpisode(seriesName, episodeName, kwikUrl, manifestUrl, posterUrl)

    suspend fun downloadAnimeBatch(
        seriesName: String,
        animeSession: String,
        episodes: List<AnimeEpisode>,
        preferredAudio: String,
        preferredQuality: Int,
        posterUrl: String = ""
    ) = downloadController.enqueueAnimeBatch(
        seriesName, animeSession, episodes, preferredAudio, preferredQuality, posterUrl
    )

    suspend fun downloadMangaChapter(
        seriesName: String,
        chapter: MangaChapter,
        format: DownloadFormat,
        posterUrl: String = ""
    ) = downloadController.enqueueMangaChapter(seriesName, chapter, format, posterUrl)

    suspend fun downloadMangaBatch(
        seriesName: String,
        chapters: List<MangaChapter>,
        format: DownloadFormat,
        posterUrl: String = ""
    ) = downloadController.enqueueMangaBatch(seriesName, chapters, format, posterUrl)

    suspend fun pauseDownload(taskId: Long) = downloadController.pause(taskId)
    suspend fun resumeDownload(taskId: Long) = downloadController.resume(taskId)
    suspend fun cancelOrDeleteDownload(taskId: Long) = downloadController.cancelOrDelete(taskId)
}
