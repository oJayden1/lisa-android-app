package com.cosmicoppai.lisa.data.remote

import com.cosmicoppai.lisa.model.MangaChapter
import com.cosmicoppai.lisa.model.MangaDescription
import com.cosmicoppai.lisa.model.MangaDetailsResult
import com.cosmicoppai.lisa.model.MangaSearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class MangaSearchPage(
    val results: List<MangaSearchItem>,
    val hasNext: Boolean,
    val hasPrev: Boolean
)

data class MangaChapterMedia(
    val imageUrls: List<String>,
    val fallbackServers: List<List<String>> = emptyList(),
    val seriesName: String,
    val chapterTitle: String
)

@Singleton
class MangaKatanaScraper @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private suspend fun executeGet(url: String, referer: String = "$SITE_URL/"): String = withContext(Dispatchers.IO) {
        val targetUrl = normalizeSiteUrl(url)
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", CloudflareCookieResolver.DEFAULT_USER_AGENT)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .build()

                okHttpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $targetUrl")
                    return@withContext body
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 3) delay(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Failed to load $targetUrl")
    }

    suspend fun searchManga(query: String, page: Int = 1, limit: Int = 20): MangaSearchPage {
        val url = "$SITE_URL/page/${page.coerceAtLeast(1)}".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("search_by", "book_name")
            .build()
            .toString()

        val html = executeGet(url)
        return parseSearchHtml(html, limit)
    }

    fun parseSearchHtml(html: String, limit: Int = 20): MangaSearchPage {
        val doc = Jsoup.parse(html)
        return if (doc.selectFirst("div#book_list") != null) {
            val pag = doc.selectFirst("ul.uk-pagination")
            val hasPrev = pag?.selectFirst("a.prev") != null
            val hasNext = pag?.selectFirst("a.next") != null
            val list = parseSearchList(doc).take(limit.coerceIn(1, 20))
            MangaSearchPage(results = list, hasNext = hasNext, hasPrev = hasPrev)
        } else {
            val single = parseSingleDetailAsSearchItem(doc)
            MangaSearchPage(results = listOfNotNull(single), hasNext = false, hasPrev = false)
        }
    }

    private fun parseSearchList(doc: org.jsoup.nodes.Document): List<MangaSearchItem> {
        val bookList = doc.selectFirst("div#book_list") ?: return emptyList()
        return bookList.select("div.item").mapNotNull { item ->
            val textDiv = item.selectFirst("div.text") ?: return@mapNotNull null
            val title = textDiv.selectFirst("a")?.text()?.trim().orEmpty()
            val chpText = textDiv.selectFirst("span")?.text()?.trim()?.removePrefix("- ")?.split(Regex("""\s+""")).orEmpty()
            val totalChps = chpText.firstOrNull()?.toDoubleOrNull()
                ?: chpText.lastOrNull()?.toDoubleOrNull()
                ?: 0.0
            val genres = item.select("div.genres a").map { it.text().trim() }.filter { it.isNotEmpty() }
            val media = item.selectFirst("div.media")
            val mediaA = media?.selectFirst("div.wrap_img a")
            val poster = extractImgUrl(mediaA?.selectFirst("img"))
            val status = media?.selectFirst("div.status")?.text()?.trim()?.replaceFirstChar { it.uppercase() }.orEmpty()
            val session = normalizeSiteUrl(mediaA?.attr("href").orEmpty())

            if (title.isBlank() || session.isBlank()) return@mapNotNull null
            MangaSearchItem(
                title = title,
                totalChps = formatChapterCount(totalChps),
                genres = genres,
                poster = poster,
                status = status,
                session = session
            )
        }
    }

    private fun parseSingleDetailAsSearchItem(doc: org.jsoup.nodes.Document): MangaSearchItem? {
        val info = doc.selectFirst("div.info") ?: return null
        val title = info.selectFirst("h1.heading")?.text()?.trim().orEmpty()
        val meta = info.selectFirst("ul.meta.d-table") ?: info.selectFirst("ul.meta")
        val firstChpText = doc.select("div.chapter").firstOrNull()?.text().orEmpty()
        val chpNumber = Regex("""\d+(\.\d+)?""").find(firstChpText)?.value?.toDoubleOrNull() ?: 0.0
        val genres = meta?.select("div.genres a")?.map { it.text().trim() }.orEmpty()
        val poster = extractImgUrl(doc.selectFirst("div.cover img"))
        val status = meta?.selectFirst("div.status")?.text()?.trim()?.replaceFirstChar { it.uppercase() }.orEmpty()
        val mangaUrl = normalizeSiteUrl(doc.selectFirst("meta[property=og:url]")?.attr("content").orEmpty())
        if (title.isBlank() || mangaUrl.isBlank()) return null

        return MangaSearchItem(
            title = title,
            totalChps = formatChapterCount(chpNumber),
            genres = genres,
            poster = poster,
            status = status,
            session = mangaUrl
        )
    }

    suspend fun getMangaDetail(
        mangaSession: String,
        inMyListCheck: suspend (String) -> Boolean = { false }
    ): MangaDetailsResult {
        val html = executeGet(mangaSession)
        val parsed = parseMangaDetailHtml(html, mangaSession)
        return parsed.copy(inMyList = inMyListCheck(parsed.mangaId))
    }

    fun parseMangaDetailHtml(html: String, mangaSession: String): MangaDetailsResult {
        val doc = Jsoup.parse(html)
        val chapters = doc.select("div.chapters tr").mapNotNull { row ->
            val chpDiv = row.selectFirst("div.chapter") ?: return@mapNotNull null
            val chpHref = normalizeSiteUrl(chpDiv.selectFirst("a")?.attr("href").orEmpty())
            if (chpHref.isBlank()) return@mapNotNull null
            MangaChapter(chpName = chpDiv.text().trim(), chpSession = chpHref)
        }

        var altName: String? = null
        var author: String? = null
        doc.select("ul.meta div.alt_name, ul.meta div.authors").forEach { div ->
            if (div.hasClass("alt_name")) altName = div.text().trim()
            if (div.hasClass("authors")) author = div.text().trim()
        }
        val summary = doc.selectFirst("div.summary p")?.text()?.trim().orEmpty()
        val coverPoster = extractImgUrl(doc.selectFirst("div.cover img, .cover img"))
            .ifBlank { normalizeImageUrl(doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()) }

        val recommendations = mutableListOf<MangaSearchItem>()
        doc.select("div#hot_book div.widget").forEach { widget ->
            val widgetTitle = widget.selectFirst("div.widget-title span, div.widget-title")?.text()?.lowercase().orEmpty()
            if (widgetTitle.contains("similar")) {
                widget.select("div.item").forEach { rec ->
                    val poster = extractRecPoster(rec)
                    val recData = rec.selectFirst("div.text") ?: rec
                    val titleEl = recData.selectFirst("h3") ?: recData.selectFirst(".title")
                    val titleLink = titleEl?.selectFirst("a") ?: rec.selectFirst("a[href*='/manga/']")
                    val title = titleEl?.text()?.trim().orEmpty().ifBlank {
                        titleLink?.text()?.trim().orEmpty()
                    }
                    val href = normalizeSiteUrl(titleLink?.attr("href").orEmpty())
                    val chpStr = recData.selectFirst("div.chapter, .chapter")?.text().orEmpty()
                    val chpNum = Regex("""\d+""").find(chpStr)?.value ?: "1"
                    val status = recData.selectFirst("div.status, .status")?.text()?.trim().orEmpty()
                    if (title.isNotBlank() && href.isNotBlank()) {
                        recommendations.add(
                            MangaSearchItem(
                                title = title,
                                totalChps = chpNum,
                                poster = poster,
                                status = status,
                                session = href
                            )
                        )
                    }
                }
            }
        }

        val mangaId = mangaSession.substringAfter("/manga/").substringAfterLast(".", "")
            .ifBlank { mangaSession.hashCode().toString() }

        return MangaDetailsResult(
            mangaId = mangaId,
            chapters = chapters,
            description = MangaDescription(
                altName = altName,
                author = author,
                summary = summary,
                poster = coverPoster
            ),
            inMyList = false,
            recommendations = recommendations
        )
    }

    suspend fun getChapterImages(chpUrl: String): MangaChapterMedia {
        val html = executeGet(chpUrl, referer = "$SITE_URL/")
        return parseChapterImagesHtml(html, chpUrl)
    }

    fun parseChapterImagesHtml(html: String, chpUrl: String): MangaChapterMedia {
        val serverCandidates = linkedMapOf<String, List<String>>()

        arrayVarRegex.findAll(html).forEach { match ->
            val varName = match.groupValues[1]
            val arrayBody = match.groupValues[2]
            val urls = quotedStringRegex.findAll(arrayBody)
                .map { normalizeImageUrl(it.groupValues[1]) }
                .filter { isLikelyMangaPageUrl(it) }
                .toList()
            if (urls.isNotEmpty()) {
                serverCandidates[varName] = urls
            }
        }

        // Order primary/backup JS arrays cleanly: thzq first, then ytaw, htnc, then any remaining
        val priority = listOf("thzq", "ytaw", "htnc")
        val orderedServers = mutableListOf<List<String>>()
        priority.forEach { key ->
            serverCandidates[key]?.let { orderedServers.add(it) }
        }
        serverCandidates.forEach { (key, urls) ->
            if (key !in priority) orderedServers.add(urls)
        }

        val doc = Jsoup.parse(html)
        val domImages = doc.select("div#imgs img")
            .map { extractImgUrl(it) }
            .filter { isLikelyMangaPageUrl(it) }
        if (domImages.isNotEmpty()) {
            orderedServers.add(domImages)
        }

        val uniqueServers = orderedServers.distinct()
        val primary = uniqueServers.firstOrNull().orEmpty()
        val fallbacks = uniqueServers.drop(1)

        val seriesName = chpUrl.split("/").getOrElse(4) { "manga" }.substringBefore(".")
        val chapterTitle = doc.selectFirst("select[name=chapter_select] option[selected]")?.text()?.trim()
            ?: chpUrl.substringAfterLast("/").ifBlank { "Chapter" }

        return MangaChapterMedia(
            imageUrls = primary,
            fallbackServers = fallbacks,
            seriesName = seriesName,
            chapterTitle = chapterTitle
        )
    }

    private fun extractRecPoster(rec: Element): String {
        rec.select("div.wrap_img img, a.wrap_img img, .wrap_img img, img").forEach { img ->
            val fromImg = extractImgUrl(img)
            if (fromImg.isNotBlank()) return fromImg
        }

        // Fallback to <noscript><img ...></noscript> whether Jsoup parsed children or raw data
        rec.select("noscript").forEach { noscript ->
            val directImg = noscript.selectFirst("img")
            val fromDirect = extractImgUrl(directImg)
            if (fromDirect.isNotBlank()) return fromDirect

            val raw = noscript.data().ifBlank { noscript.html() }
            if (raw.isNotBlank()) {
                val parsedImg = Jsoup.parseBodyFragment(raw).selectFirst("img")
                val fromParsed = extractImgUrl(parsedImg)
                if (fromParsed.isNotBlank()) return fromParsed
            }
        }

        // Fallback to data-bg / data-background / inline CSS background-image on wrapper elements
        val containers = listOfNotNull(
            rec.selectFirst("div.wrap_img, a.wrap_img, .wrap_img"),
            rec.selectFirst("a"),
            rec
        )
        for (el in containers) {
            val attrUrl = listOf(
                el.attr("data-bg"),
                el.attr("data-background"),
                el.attr("data-src"),
                el.attr("data-lazy-src")
            ).firstOrNull { isValidImageCandidate(it) }
            if (!attrUrl.isNullOrBlank()) return normalizeImageUrl(attrUrl)

            val style = el.attr("style")
            if (style.isNotBlank()) {
                val bgMatch = cssBgUrlRegex.find(style)?.groupValues?.getOrNull(1)?.trim()
                if (!bgMatch.isNullOrBlank() && isValidImageCandidate(bgMatch)) {
                    return normalizeImageUrl(bgMatch)
                }
            }
        }
        return ""
    }

    private fun extractImgUrl(img: Element?): String {
        if (img == null) return ""
        val srcsetFirst = listOf(img.attr("data-srcset"), img.attr("srcset"))
            .mapNotNull { raw ->
                raw.split(",")
                    .map { it.trim().substringBefore(" ").trim() }
                    .firstOrNull { isValidImageCandidate(it) }
            }
            .firstOrNull()
            .orEmpty()

        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-lazy"),
            img.attr("data-url"),
            srcsetFirst,
            img.attr("src")
        )
        val raw = candidates.firstOrNull { isValidImageCandidate(it) }.orEmpty()
        return normalizeImageUrl(raw)
    }

    private fun isValidImageCandidate(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        if (lower.startsWith("data:image/")) return false
        if (lower.contains("blank.gif") ||
            lower.contains("loading.gif") ||
            lower.contains("spacer.gif") ||
            lower.contains("pixel.gif") ||
            lower.contains("transparent.gif")
        ) {
            return false
        }
        return true
    }

    private fun normalizeImageUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$SITE_URL$trimmed"
            else -> "$SITE_URL/$trimmed"
        }
    }

    private fun normalizeSiteUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$SITE_URL$trimmed"
            else -> "$SITE_URL/$trimmed"
        }
    }

    private fun isLikelyMangaPageUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val path = url.substringBefore("?").lowercase()
        if (path.endsWith(".js") || path.endsWith(".css")) return false
        return true
    }

    private fun formatChapterCount(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    companion object {
        const val SITE_URL = "https://mangakatana.com"
        private val arrayVarRegex = Regex(
            """var\s+(thzq|ytaw|htnc|[a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*\[(.*?)\]\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val quotedStringRegex = Regex("""['"]([^'"]+)['"]""")
        private val cssBgUrlRegex = Regex("""url\(\s*['"]?([^'")\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    }
}
