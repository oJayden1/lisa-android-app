package com.cosmicoppai.lisa.data.remote

import com.cosmicoppai.lisa.data.local.UserPreferences
import com.cosmicoppai.lisa.model.AnimeDescription
import com.cosmicoppai.lisa.model.AnimeDetailsPage
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.StreamOption
import kotlinx.coroutines.CancellationException
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimepaheScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cookieResolver: CloudflareCookieResolver,
    private val userPreferences: UserPreferences,
    private val json: Json
) {
    private val deadMirrorUntilMs = ConcurrentHashMap<String, Long>()

    private suspend fun configuredBaseUrl(): String {
        val prefUrl = userPreferences.settings.first().animepaheBaseUrl.ifBlank { DEFAULT_SITE_URL }.trimEnd('/')
        val resolved = cookieResolver.resolvedOriginUrl?.trimEnd('/')
        return if (!resolved.isNullOrBlank() && prefUrl in MIRROR_URLS) resolved else prefUrl
    }

    private suspend fun candidateMirrors(): List<String> {
        val primary = configuredBaseUrl()
        val all = (listOf(primary) + MIRROR_URLS).distinct()
        val now = System.currentTimeMillis()
        val healthy = all.filter { (deadMirrorUntilMs[it] ?: 0L) <= now }
        return healthy.ifEmpty { all }
    }

    private suspend fun <T> withMirrorFailover(block: suspend (site: String) -> T): T {
        val mirrors = candidateMirrors()
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            try {
                val result = block(mirror)
                deadMirrorUntilMs.remove(mirror)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: InteractiveCaptchaException) {
                // Active AnimePahe mirrors share the same Cloudflare zone; fail fast so the user can verify cleanly.
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (isUnreachableHostError(t)) {
                    deadMirrorUntilMs[mirror] = System.currentTimeMillis() + DEAD_MIRROR_COOLDOWN_MS
                }
            }
        }
        throw lastError ?: IOException("Failed to reach AnimePahe")
    }

    private fun isUnreachableHostError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is UnknownHostException || cur is ConnectException || cur is SocketTimeoutException) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private suspend fun executeGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        requireCookies: Boolean = true,
        siteOrigin: String? = null,
        directWebViewNavigation: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val site = siteOrigin ?: configuredBaseUrl()
        var userAgent = if (requireCookies) {
            cookieResolver.ensureSession(CloudflareCookieResolver.ANIMEPAHE_SITE, site)
        } else {
            cookieResolver.cachedUserAgent
        }

        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/javascript, text/html, */*; q=0.01")
                    .header("Accept-Language", "en-GB,en;q=0.9,ja-JP;q=0.8,ja;q=0.7,en-US;q=0.6")
                if (url.contains("/api?")) {
                    builder.header("X-Requested-With", "XMLHttpRequest")
                }
                if (!referer.isNullOrBlank()) {
                    builder.header("Referer", referer)
                }
                extraHeaders.forEach { (k, v) -> builder.header(k, v) }

                okHttpClient.newCall(builder.build()).execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    val finalUrl = resp.request.url.toString()
                    val blocked = resp.code == 403 ||
                        resp.code == 503 ||
                        CloudflareCookieResolver.isChallengeInterstitial(bodyText) ||
                        CloudflareCookieResolver.hasInteractiveCaptchaMarker(bodyText)

                    if (!blocked && resp.isSuccessful && bodyText.isNotBlank()) {
                        return@withContext bodyText to finalUrl
                    }

                    // Fall back to persistent offscreen Chromium WebView (no UI popup)
                    if (blocked || !resp.isSuccessful) {
                        val wvResult = cookieResolver.fetchViaHiddenWebView(
                            url = url,
                            originUrl = site,
                            referer = referer,
                            extraHeaders = extraHeaders,
                            directPageNavigation = directWebViewNavigation
                        )
                        if (wvResult.hasInteractiveCaptcha) {
                            throw InteractiveCaptchaException(
                                message = "AnimePahe requires human verification on this network.",
                                siteUrl = wvResult.resolvedOrigin.ifBlank { site }
                            )
                        }
                        if (wvResult.statusCode in 200..299 &&
                            wvResult.body.isNotBlank() &&
                            !CloudflareCookieResolver.isChallengeInterstitial(wvResult.body)
                        ) {
                            return@withContext wvResult.body to wvResult.finalUrl.ifBlank { url }
                        }
                        throw IOException("HTTP ${wvResult.statusCode} from ${wvResult.finalUrl.ifBlank { url }}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InteractiveCaptchaException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (isUnreachableHostError(t)) {
                    throw t
                }
                if (attempt == 0) {
                    userAgent = cookieResolver.cachedUserAgent
                    delay(500L)
                }
            }
        }
        throw lastError ?: IOException("Failed to fetch $url")
    }

    suspend fun searchAnime(query: String, limit: Int = 9): List<AnimeSearchItem> = withMirrorFailover { site ->
        val url = "$site/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "search")
            .addQueryParameter("q", query)
            .build()
            .toString()

        val (body, _) = executeGet(url, referer = "$site/", siteOrigin = site)
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return@withMirrorFailover emptyList()

        data.take(limit.coerceIn(1, 20)).map { element ->
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

    suspend fun resolveSessionByAnimeId(animeId: Int): String = withMirrorFailover { site ->
        val (_, finalUrl) = executeGet("$site/a/$animeId", referer = "$site/", siteOrigin = site)
        finalUrl.substringAfter("/anime/").substringBefore("?").trim('/')
    }

    suspend fun getEpisodeDetails(
        animeSession: String,
        page: Int = 1,
        inMyListCheck: suspend (Int) -> Boolean = { false }
    ): AnimeDetailsPage = withMirrorFailover { site ->
        val url = "$site/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "release")
            .addQueryParameter("id", animeSession)
            .addQueryParameter("sort", "episode_asc")
            .addQueryParameter("page", page.toString())
            .build()
            .toString()

        val (body, _) = executeGet(url, referer = "$site/anime/$animeSession", siteOrigin = site)
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

        var description: AnimeDescription? = null
        var recommendations: List<AnimeSearchItem> = emptyList()
        if (page == 1) {
            val (animePageHtml, _) = executeGet("$site/anime/$animeSession", referer = "$site/", siteOrigin = site)
            description = parseAnimeDescriptionHtml(animePageHtml)
            recommendations = parseRecommendationsHtml(animePageHtml)
        }
        val inMyList = if (description != null && description.animeId > 0) {
            inMyListCheck(description.animeId)
        } else {
            false
        }

        AnimeDetailsPage(
            episodes = episodes,
            currentPage = page,
            totalPages = lastPage,
            hasNextPage = hasNext,
            hasPrevPage = hasPrev,
            description = description,
            inMyList = inMyList,
            recommendations = recommendations,
            includesRecommendations = page == 1
        )
    }

    suspend fun getAnimeDescription(animeSession: String): AnimeDescription = withMirrorFailover { site ->
        val (html, _) = executeGet("$site/anime/$animeSession", referer = "$site/", siteOrigin = site)
        parseAnimeDescriptionHtml(html)
    }

    fun parseAnimeDescriptionHtml(html: String): AnimeDescription = Companion.parseAnimeDescriptionHtml(html)

    suspend fun getRecommendations(animeSession: String): List<AnimeSearchItem> = withMirrorFailover { site ->
        val (html, _) = executeGet("$site/anime/$animeSession", referer = "$site/", siteOrigin = site)
        parseRecommendationsHtml(html)
    }

    fun parseRecommendationsHtml(html: String): List<AnimeSearchItem> = Companion.parseRecommendationsHtml(html)

    suspend fun getStreamData(animeSession: String, epSession: String): Map<String, List<StreamOption>> =
        withMirrorFailover { site ->
            val (html, _) = executeGet(
                url = "$site/play/$animeSession/$epSession",
                referer = "$site/anime/$animeSession",
                siteOrigin = site
            )
            parseStreamMenuHtml(html)
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
        val site = configuredBaseUrl()
        val parsedKwik = kwikUrl.toHttpUrlOrNull()
        val kwikOrigin = parsedKwik?.let { "${it.scheme}://${it.host}" } ?: "https://kwik.cx"
        val (html, _) = executeGet(
            url = kwikUrl,
            referer = "$site/",
            requireCookies = false,
            siteOrigin = kwikOrigin,
            directWebViewNavigation = true
        )
        return try {
            KwikJsUnpacker.extractFromHtml(html)
        } catch (t: Throwable) {
            // If OkHttp got HTML without the packed script (e.g., JS challenge), retry in silent WebView
            val wvResult = cookieResolver.fetchViaHiddenWebView(
                url = kwikUrl,
                originUrl = kwikOrigin,
                referer = "$site/",
                directPageNavigation = true
            )
            KwikJsUnpacker.extractFromHtml(wvResult.body)
        }
    }

    suspend fun getAllEpisodeSessionsOnPage(animeSession: String, page: Int = 1): List<AnimeEpisode> {
        return getEpisodeDetails(animeSession, page).episodes
    }

    companion object {
        const val DEFAULT_SITE_URL = "https://animepahe.pw"
        private const val DEAD_MIRROR_COOLDOWN_MS = 5L * 60L * 1000L
        val MIRROR_URLS = listOf(
            "https://animepahe.pw",
            "https://animepahe.com"
        )
        val KWIK_HEADERS = mapOf(
            "Origin" to "https://kwik.cx",
            "Referer" to "https://kwik.cx/",
            "Accept" to "*/*"
        )

        fun parseAnimeDescriptionHtml(html: String): AnimeDescription {
            val doc = Jsoup.parse(html)
            val bookmarkHref = doc.selectFirst("a.fa-link")?.attr("href").orEmpty()
            val animeId = bookmarkHref.replace("/a/", "").trim().toIntOrNull() ?: 0
            val synopsis = doc.selectFirst("div.anime-synopsis")?.text()?.replace("\"", "").orEmpty()

            val posterEl = doc.selectFirst("div.anime-poster img, .anime-poster img")
            val rawPoster = listOf(
                posterEl?.attr("data-src").orEmpty(),
                posterEl?.attr("data-lazy-src").orEmpty(),
                posterEl?.attr("src").orEmpty(),
                doc.selectFirst("div.anime-poster a[href]")?.attr("href").orEmpty(),
                doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            ).firstOrNull { candidate ->
                val trimmed = candidate.trim()
                trimmed.isNotBlank() &&
                    !trimmed.startsWith("data:image", ignoreCase = true) &&
                    (trimmed.contains("/posters/", ignoreCase = true) ||
                        trimmed.endsWith(".jpg", ignoreCase = true) ||
                        trimmed.endsWith(".jpeg", ignoreCase = true) ||
                        trimmed.endsWith(".png", ignoreCase = true) ||
                        trimmed.endsWith(".webp", ignoreCase = true) ||
                        trimmed.startsWith("http", ignoreCase = true))
            }.orEmpty().trim()
            val normalizedPoster = normalizeUrl(rawPoster).takeIf { it.isNotBlank() }

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
                themes = details["themes"]?.split(Regex("""\s+"""))?.filter { it.isNotBlank() } ?: emptyList(),
                poster = normalizedPoster
            )
        }

        fun parseRecommendationsHtml(html: String): List<AnimeSearchItem> {
            val doc = Jsoup.parse(html)
            val col2s = doc.select("div.col-2")
            val col9s = doc.select("div.col-9")
            val count = minOf(col2s.size, col9s.size, 10)
            val result = mutableListOf<AnimeSearchItem>()
            val metaRegex = Regex(
                """([A-Za-z0-9 ]+?)\s*-\s*(\d+|\?)\s*Episodes?\s*(?:\(([^)]+)\))?""",
                RegexOption.IGNORE_CASE
            )
            val seasonRegex = Regex(
                """\b(Winter|Spring|Summer|Fall)\s+(\d{4})\b""",
                RegexOption.IGNORE_CASE
            )

            for (i in 0 until count) {
                val col2 = col2s[i]
                val col9 = col9s[i]
                val titleLink = col9.selectFirst("h5 a, strong a, h4 a, h3 a, a[href*='/anime/']")
                    ?: col9.selectFirst("h5, strong, a")
                val title = titleLink?.text()?.trim().orEmpty().ifBlank {
                    col9.ownText().trim()
                }

                val metaClone = col9.clone()
                metaClone.selectFirst("h5, h4, h3, strong")?.remove()
                    ?: metaClone.selectFirst("a[href*='/anime/']")?.remove()
                val metaText = metaClone.text().trim().let { remaining ->
                    if (title.isNotBlank() && remaining.startsWith(title)) {
                        remaining.removePrefix(title).trim()
                    } else {
                        remaining
                    }
                }

                val metaMatch = metaRegex.find(metaText)
                val type = metaMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "TV" }
                val ep = metaMatch?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { "0" }
                val status = metaMatch?.groupValues?.getOrNull(3)?.trim().orEmpty()

                val seasonMatch = seasonRegex.find(metaText)
                val season = seasonMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val year = seasonMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()

                val href = col2.selectFirst("a[href]")?.attr("href").orEmpty()
                    .ifBlank { titleLink?.attr("href").orEmpty() }
                val session = if (href.contains("/anime/")) {
                    href.substringAfter("/anime/").substringBefore("/").substringBefore("?").trim()
                } else {
                    href.trim('/').split("/").getOrElse(1) { "" }
                }

                val imgEl = col2.selectFirst("img")
                val rawPoster = listOf(
                    imgEl?.attr("data-src").orEmpty(),
                    imgEl?.attr("data-lazy-src").orEmpty(),
                    imgEl?.attr("src").orEmpty()
                ).firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }.orEmpty()
                val poster = normalizeUrl(rawPoster)

                if (session.isNotBlank() && title.isNotBlank()) {
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

        private fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("//") -> "https:$trimmed"
                else -> trimmed
            }
        }
    }
}
