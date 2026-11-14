package com.cosmicoppai.lisa.model

import kotlinx.serialization.Serializable

enum class AppMode {
    ANIME,
    MANGA;

    val label: String get() = if (this == ANIME) "Anime" else "Manga"
}

enum class DownloadFormat(val extension: String) {
    MP4("mp4"),
    JPG("jpg"),
    PDF("pdf")
}

enum class DownloadStatus {
    SCHEDULED,
    STARTED,
    PAUSED,
    DOWNLOADED,
    FAILED
}

enum class MediaType {
    ANIME,
    MANGA
}

@Serializable
data class AnimeSearchItem(
    val title: String,
    val noOfEpisodes: String = "0",
    val type: String = "TV",
    val status: String = "",
    val season: String = "",
    val year: String = "",
    val score: Double = 0.0,
    val session: String,
    val poster: String = "",
    val animeId: Int? = null
)

@Serializable
data class AnimeEpisode(
    val epNo: String,
    val epSession: String,
    val snapshot: String = "",
    val duration: String = ""
)

@Serializable
data class AnimeDescription(
    val animeId: Int = 0,
    val synopsis: String = "",
    val engName: String? = null,
    val studio: String? = null,
    val youtubeUrl: String? = null,
    val externalLinks: Map<String, String> = emptyMap(),
    val type: String = "TV",
    val status: String = "Finished Airing",
    val aired: String? = null,
    val season: String = "",
    val year: String = "",
    val duration: String = "",
    val themes: List<String> = emptyList()
)

data class AnimeDetailsPage(
    val episodes: List<AnimeEpisode>,
    val currentPage: Int,
    val totalPages: Int,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    val description: AnimeDescription? = null,
    val inMyList: Boolean = false
)

@Serializable
data class StreamOption(
    val resolution: String,
    val kwikUrl: String,
    val audio: String
)

data class ResolvedStreamVariant(
    val resolution: Int,
    val audio: String,
    val manifestUrl: String,
    val fileName: String
)

@Serializable
data class MangaSearchItem(
    val title: String,
    val totalChps: String = "0",
    val genres: List<String> = emptyList(),
    val poster: String = "",
    val status: String = "",
    val session: String
)

@Serializable
data class MangaChapter(
    val chpName: String,
    val chpSession: String
)

@Serializable
data class MangaDescription(
    val altName: String? = null,
    val author: String? = null,
    val summary: String = ""
)

data class MangaDetailsResult(
    val mangaId: String,
    val chapters: List<MangaChapter>,
    val description: MangaDescription,
    val inMyList: Boolean,
    val recommendations: List<MangaSearchItem>
)

data class ExploreItem(
    val rank: String,
    val poster: String,
    val title: String,
    val type: String,
    val countLabel: String,
    val score: String,
    val mediaType: MediaType
)

data class ExplorePage(
    val items: List<ExploreItem>,
    val offset: Int,
    val hasNext: Boolean,
    val hasPrev: Boolean
)

enum class AnimeExploreCategory(val key: String, val label: String, val malQuery: String) {
    ALL("all_anime", "All", ""),
    AIRING("airing", "Airing", "airing"),
    UPCOMING("upcoming", "Upcoming", "upcoming"),
    TV("tv", "TV", "tv"),
    MOVIE("movie", "Movie", "movie"),
    BY_POPULARITY("by_popularity", "By Popularity", "bypopularity"),
    OVA("ova", "OVA", "ova"),
    ONA("ona", "ONA", "ona"),
    SPECIAL("special", "Special", "special"),
    FAVORITE("favorite", "Favorite", "favorite")
}

enum class MangaExploreCategory(val key: String, val label: String, val malQuery: String) {
    MANGA("manga", "Manga", "manga"),
    ONESHOTS("oneshots", "One Shots", "oneshots"),
    DOUJIN("doujin", "Doujin", "doujin"),
    LIGHT_NOVELS("light_novels", "Light Novels", "lightnovels"),
    NOVELS("novels", "Novels", "novels"),
    MANHWA("manhwa", "Manhwa", "manhwa"),
    MANHUA("manhua", "Manhua", "manhua"),
    BY_POPULARITY("by_popularity", "By Popularity", "bypopularity"),
    FAVOURITE("favourite", "Favorite", "favourite")
}
