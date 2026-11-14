package com.cosmicoppai.lisa.data.remote

import com.cosmicoppai.lisa.data.local.UserPreferences
import com.cosmicoppai.lisa.model.AnimeDescription
import com.cosmicoppai.lisa.model.AnimeDetailsPage
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.StreamOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimepaheScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cookieResolver: CloudflareCookieResolver,
    private val userPreferences: UserPreferences,
    private val json: Json
) {
    private suspend fun baseUrl(): String =
        userPreferences.settings.first().animepaheBaseUrl.ifBlank { DEFAULT_SITE_URL }

    private suspend fun executeGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        requireCookies: Boolean = true
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val site = baseUrl()
        var userAgent = if (requireCookies) {
            cookieResolver.ensureSession(CloudflareCookieResolver.ANIMEPAHE_SITE, site)
        } else {
            cookieResolver.cachedUserAgent
        }

        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/javascript, text/html, */*; q=0.01")
                    .header("Accept-Language", "en-GB,en;q=0.9,ja-JP;q=0.8,ja;q=0.7,en-US;q=0.6")
                if (!referer.isNullOrBlank()) {
                    builder.header("Referer", referer)
                }
                extraHeaders.forEach { (k, v) -> builder.header(k, v) }

                okHttpClient.newCall(builder.build()).execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    val finalUrl = resp.request.url.toString()
                    if (resp.code == 403 || resp.code == 503) {
                        if (requireCookies && attempt < 2) {
                            userAgent = cookieResolver.ensureSession(
                                siteName = CloudflareCookieResolver.ANIMEPAHE_SITE,
                                siteUrl = site,
                                forceRefresh = true
                            )
                            throw IOException("Cloudflare/DDoS-Guard challenge (${resp.code}), refreshed session")
                        }
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} from $url")
                    }
                    return@withContext bodyText to finalUrl
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 3) delay((1000L * (attempt + 1)))
            }
        }
        throw lastError ?: IOException("Failed to fetch $url")
    }

    suspend fun searchAnime(query: String, limit: Int = 9): List<AnimeSearchItem> {
        val site = baseUrl()
        val url = "$site/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "search")
            .addQueryParameter("q", query)
            .build()
            .toString()

        val (body, _) = executeGet(url, referer = site)
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()

        return data.take(limit.coerceIn(1, 20)).map { element ->
            val obj = element.jsonObject
            AnimeSearchItem(
                title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                noOfEpisodes = obj["episodes"]?.jsonPrimitive?.content ?: "0",
                type = obj["type"]?.jsonPrimitive?.content ?: "TV",
                status = obj["status"]?.jsonPrimitive?.content.orEmpty(),
                season = obj["season"]?.jsonPrimitive?.content.orEmpty(),
                year = obj["year"]?.jsonPrimitive?.content.orEmpty(),
                score = obj["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                session = obj["session"]?.jsonPrimitive?.content.orEmpty(),
                poster = obj["poster"]?.jsonPrimitive?.content.orEmpty(),
                animeId = obj["id"]?.jsonPrimitive?.intOrNull
            )
        }
    }

    suspend fun resolveSessionByAnimeId(animeId: Int): String {
        val site = baseUrl()
        val (_, finalUrl) = executeGet("$site/a/$animeId", referer = site)
        return finalUrl.substringAfter("/anime/").substringBefore("?").trim('/')
    }

    suspend fun getEpisodeDetails(
        animeSession: String,
        page: Int = 1,
        inMyListCheck: suspend (Int) -> Boolean = { false }
    ): AnimeDetailsPage {
        val site = baseUrl()
        val url = "$site/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "release")
            .addQueryParameter("id", animeSession)
            .addQueryParameter("sort", "episode_asc")
            .addQueryParameter("page", page.toString())
            .build()
            .toString()

        val (body, _) = executeGet(url, referer = "$site/$animeSession")
        val root = json.parseToJsonElement(body).jsonObject
        val lastPage = root["last_page"]?.jsonPrimitive?.intOrNull ?: 1
        val hasNext = root["next_page_url"]?.jsonPrimitive?.content?.let { it != "null" && it.isNotBlank() } == true
        val hasPrev = root["prev_page_url"]?.jsonPrimitive?.content?.let { it != "null" && it.isNotBlank() } == true

        val epArray = root["data"]?.jsonArray ?: emptyList()
        val episodes = epArray.map { el ->
            val obj = el.jsonObject
            AnimeEpisode(
                epNo = obj["episode"]?.jsonPrimitive?.content ?: "0",
                epSession = obj["session"]?.jsonPrimitive?.content.orEmpty(),
                snapshot = obj["snapshot"]?.jsonPrimitive?.content.orEmpty(),
                duration = obj["duration"]?.jsonPrimitive?.content.orEmpty()
            )
        }

        val description = if (page == 1) getAnimeDescription(animeSession) else null
        val inMyList = if (description != null && description.animeId > 0) {
            inMyListCheck(description.animeId)
        } else {
            false
        }

        return AnimeDetailsPage(
            episodes = episodes,
            currentPage = page,
            totalPages = lastPage,
            hasNextPage = hasNext,
            hasPrevPage = hasPrev,
            description = description,
            inMyList = inMyList
        )
    }

    suspend fun getAnimeDescription(animeSession: String): AnimeDescription {
        val site = baseUrl()
        val (html, _) = executeGet("$site/anime/$animeSession", referer = "$site/$animeSession")
        return parseAnimeDescriptionHtml(html)
    }

    fun parseAnimeDescriptionHtml(html: String): AnimeDescription {
        val doc = Jsoup.parse(html)
        val bookmarkHref = doc.selectFirst("a.fa-link")?.attr("href").orEmpty()
        val animeId = bookmarkHref.replace("/a/", "").trim().toIntOrNull() ?: 0
        val synopsis = doc.selectFirst("div.anime-synopsis")?.text()?.replace("\"", "").orEmpty()

        var youtubeUrl: String? = null
        val inlineScript = doc.select("script:not([src])").firstOrNull()?.data().orEmpty()
        for (line in inlineScript.split(";")) {
            val trimmed = line.trim().removePrefix("let ").trim()
            if (trimmed.startsWith("preview") && trimmed.contains("=")) {
                val url = trimmed.substringAfter("=").trim().trim('"', '\'', ' ')
                if (url.startsWith("https://www.youtube.com") ||
                    url.startsWith("https://youtube.com") ||
                    url.startsWith("https://youtu.be")
                ) {
                    youtubeUrl = url
                }
                break
            }
        }

        val details = mutableMapOf<String, String>()
        val externalLinks = linkedMapOf<String, String>()
        doc.select("div.anime-info p").forEach { p ->
            if (p.hasClass("external-links")) {
                p.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    val normalized = if (href.startsWith("http")) href else "https:$href"
                    externalLinks[a.text().trim()] = normalized
                }
            } else {
                val text = p.text().replace("\n", "")
                val idx = text.indexOf(':')
                if (idx > 0) {
                    details[text.substring(0, idx).lowercase().trim()] =
                        text.substring(idx + 1).trim()
                }
            }
        }

        val seasonParts = (details["season"] ?: "").split(Regex("""\s+""")).filter { it.isNotBlank() }
        val season = seasonParts.getOrElse(0) { "" }
        val year = seasonParts.getOrElse(1) { "" }

        return AnimeDescription(
            animeId = animeId,
            synopsis = synopsis,
            engName = (details["english"] ?: details["synonyms"])?.takeIf { it.isNotBlank() },
            studio = details["studio"]?.takeIf { it.isNotBlank() },
            youtubeUrl = youtubeUrl,
            externalLinks = externalLinks,
            type = details["type"]?.ifBlank { "TV" } ?: "TV",
            status = details["status"]?.ifBlank { "Finished Airing" } ?: "Finished Airing",
            aired = details["aired"]?.replace("to", " to ")?.replace("  ", " ")?.takeIf { it.isNotBlank() },
            season = season,
            year = year,
            duration = details["duration"] ?: "",
            themes = details["themes"]?.split(Regex("""\s+"""))?.filter { it.isNotBlank() } ?: emptyList()
        )
    }

    suspend fun getRecommendations(animeSession: String): List<AnimeSearchItem> {
        val site = baseUrl()
        val (html, _) = executeGet("$site/anime/$animeSession", referer = site)
        return parseRecommendationsHtml(html)
    }

    fun parseRecommendationsHtml(html: String): List<AnimeSearchItem> {
        val doc = Jsoup.parse(html)
        val col2s = doc.select("div.col-2")
        val col9s = doc.select("div.col-9")
        val count = minOf(col2s.size, col9s.size, 10)
        val result = mutableListOf<AnimeSearchItem>()

        for (i in 0 until count) {
            val col2 = col2s[i]
            val col9 = col9s[i]
            val lines = col9.text().trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val title = lines.getOrElse(0) { "" }
            val line1 = lines.getOrElse(1) { "" }
            val parts = line1.split("-")
            val type = parts.getOrElse(0) { "TV" }.trim()
            val restTokens = parts.getOrElse(1) { "" }.trim().split(Regex("""\s+"""))
            val ep = restTokens.getOrElse(0) { "0" }
            val status = restTokens.getOrElse(2) { "" }.trim('(')
            val seasonParts = lines.getOrElse(2) { "" }.split(Regex("""\s+"""))
            val season = seasonParts.getOrElse(0) { "" }
            val year = seasonParts.getOrElse(1) { "" }
            val href = col2.selectFirst("a[href]")?.attr("href").orEmpty()
            val session = href.trim('/').split("/").getOrElse(1) { "" }
            val poster = col2.selectFirst("img")?.attr("data-src")?.replace(".th.jpg", ".jpg").orEmpty()

            if (session.isNotBlank()) {
                result.add(
                    AnimeSearchItem(
                        title = title,
                        noOfEpisodes = ep,
                        type = type,
                        status = status,
                        season = season,
                        year = year,
                        score = 0.0,
                        session = session,
                        poster = poster
                    )
                )
            }
        }
        return result
    }

    suspend fun getStreamData(animeSession: String, epSession: String): Map<String, List<StreamOption>> {
        val site = baseUrl()
        val (html, _) = executeGet(
            url = "$site/play/$animeSession/$epSession",
            referer = "$site/$animeSession"
        )
        return parseStreamMenuHtml(html)
    }

    fun parseStreamMenuHtml(html: String): Map<String, List<StreamOption>> {
        val doc = Jsoup.parse(html)
        val grouped = linkedMapOf<String, MutableList<StreamOption>>()
        doc.select("div#resolutionMenu button").forEach { btn ->
            val quality = btn.attr("data-resolution")
            val kwikUrl = btn.attr("data-src")
            val audio = btn.attr("data-audio").ifBlank { "jpn" }
            if (kwikUrl.isNotBlank()) {
                grouped.getOrPut(audio) { mutableListOf() }
                    .add(StreamOption(resolution = quality, kwikUrl = kwikUrl, audio = audio))
            }
        }
        return grouped
    }

    suspend fun getHlsPlaylist(kwikUrl: String): KwikStreamResult {
        val site = baseUrl()
        val (html, _) = executeGet(kwikUrl, referer = site, requireCookies = false)
        return KwikJsUnpacker.extractFromHtml(html)
    }

    suspend fun getAllEpisodeSessionsOnPage(animeSession: String, page: Int = 1): List<AnimeEpisode> {
        return getEpisodeDetails(animeSession, page).episodes
    }

    companion object {
        const val DEFAULT_SITE_URL = "https://animepahe.com"
        val KWIK_HEADERS = mapOf(
            "Origin" to "https://kwik.cx",
            "Referer" to "https://kwik.cx/",
            "Accept" to "*/*"
        )
    }
}
