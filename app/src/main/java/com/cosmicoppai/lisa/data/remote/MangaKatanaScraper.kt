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
    val seriesName: String,
    val chapterTitle: String
)

@Singleton
class MangaKatanaScraper @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private suspend fun executeGet(url: String): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", CloudflareCookieResolver.DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .build()

                okHttpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $url")
                    return@withContext body
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 3) delay(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Failed to load $url")
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
        val titleParts = doc.title().split(" - ")
        return if (titleParts.size > 1) {
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
            val poster = mediaA?.selectFirst("img")?.attr("src").orEmpty()
            val status = media?.selectFirst("div.status")?.text()?.trim()?.replaceFirstChar { it.uppercase() }.orEmpty()
            val session = mediaA?.attr("href").orEmpty()

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
        val poster = doc.selectFirst("div.cover img")?.attr("src").orEmpty()
        val status = meta?.selectFirst("div.status")?.text()?.trim()?.replaceFirstChar { it.uppercase() }.orEmpty()
        val mangaUrl = doc.selectFirst("meta[property=og:url]")?.attr("content").orEmpty()
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
            val chpHref = chpDiv.selectFirst("a")?.attr("href").orEmpty()
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

        val recommendations = mutableListOf<MangaSearchItem>()
        doc.select("div#hot_book div.widget").forEach { widget ->
            val widgetTitle = widget.selectFirst("div.widget-title span")?.text()?.lowercase().orEmpty()
            if (widgetTitle == "similar series") {
                widget.select("div.item").forEach { rec ->
                    val poster = rec.selectFirst("div.wrap_img img")?.attr("data-src").orEmpty()
                    val recData = rec.selectFirst("div.text")
                    val titleEl = recData?.selectFirst("h3")
                    val title = titleEl?.text()?.trim().orEmpty()
                    val href = titleEl?.selectFirst("a")?.attr("href").orEmpty()
                    val chpStr = recData?.selectFirst("div.chapter")?.text().orEmpty()
                    val chpNum = Regex("""\d+""").find(chpStr)?.value ?: "1"
                    val status = recData?.selectFirst("div.status")?.text()?.trim().orEmpty()
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
            description = MangaDescription(altName = altName, author = author, summary = summary),
            inMyList = false,
            recommendations = recommendations
        )
    }

    suspend fun getChapterImages(chpUrl: String): MangaChapterMedia {
        val html = executeGet(chpUrl)
        return parseChapterImagesHtml(html, chpUrl)
    }

    fun parseChapterImagesHtml(html: String, chpUrl: String): MangaChapterMedia {
        val match = thzqRegex.find(html)
        val links = match?.groupValues?.get(1)
            ?.substringBefore(";")
            ?.trim('[', ']', ' ')
            ?.replace("'", "")
            ?.replace("\"", "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val doc = Jsoup.parse(html)
        val seriesName = chpUrl.split("/").getOrElse(4) { "manga" }.substringBefore(".")
        val chapterTitle = doc.selectFirst("select[name=chapter_select] option[selected]")?.text()?.trim()
            ?: chpUrl.substringAfterLast("/").ifBlank { "Chapter" }

        return MangaChapterMedia(
            imageUrls = links,
            seriesName = seriesName,
            chapterTitle = chapterTitle
        )
    }

    private fun formatChapterCount(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    companion object {
        const val SITE_URL = "https://mangakatana.com"
        private val thzqRegex = Regex("""var\s+thzq\s*=\s*(.*);""")
    }
}
