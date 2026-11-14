package com.cosmicoppai.lisa.data.remote

import com.cosmicoppai.lisa.model.AnimeExploreCategory
import com.cosmicoppai.lisa.model.ExploreItem
import com.cosmicoppai.lisa.model.ExplorePage
import com.cosmicoppai.lisa.model.MangaExploreCategory
import com.cosmicoppai.lisa.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MalScraper @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val cache = ConcurrentHashMap<String, ExplorePage>()

    suspend fun getTopAnime(category: AnimeExploreCategory, offset: Int = 0): ExplorePage {
        return getTop(media = MediaType.ANIME, typeQuery = category.malQuery, offset = offset)
    }

    suspend fun getTopManga(category: MangaExploreCategory, offset: Int = 0): ExplorePage {
        return getTop(media = MediaType.MANGA, typeQuery = category.malQuery, offset = offset)
    }

    private suspend fun getTop(media: MediaType, typeQuery: String, offset: Int): ExplorePage {
        val key = "${media.name}_${typeQuery}_$offset"
        cache[key]?.let { return it }

        val path = if (media == MediaType.ANIME) "topanime.php" else "topmanga.php"
        val url = "$SITE_URL/$path".toHttpUrl().newBuilder()
            .addQueryParameter("type", typeQuery)
            .addQueryParameter("limit", offset.toString())
            .build()
            .toString()

        val html = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CloudflareCookieResolver.DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("MAL returned HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }

        val page = parseTopHtml(html, media, offset)
        cache[key] = page
        return page
    }

    fun parseTopHtml(html: String, media: MediaType, offset: Int): ExplorePage {
        val doc = Jsoup.parse(html)

        var ranks = doc.select("span.rank1, span.rank2, span.rank3, span.rank4").map { it.text().trim() }
        if (ranks.isEmpty()) {
            ranks = doc.select("div.icon-ranking span").mapNotNull { el ->
                el.text().trim().takeIf { it.isNotEmpty() }
            }
        }

        var imgs = doc.select("img[width=50][height=70]").map { img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }
            stripMalImageResize(src)
        }
        if (imgs.isEmpty()) {
            imgs = doc.select("div.tile-unit").map { it.attr("data-bg") }
        }

        val titleClass = if (media == MediaType.ANIME) "anime_ranking_h3" else "manga_h3"
        var titles = doc.select("h3.$titleClass").map { it.text().trim() }
        if (titles.isEmpty()) {
            titles = doc.select("h2.title").map { it.text().trim() }
        }

        val types = mutableListOf<String>()
        val counts = mutableListOf<String>()
        doc.select("div.information").forEach { infoEl ->
            val text = infoEl.text().replace("\n", "").replace(" ", "")
            if (text.isNotEmpty()) {
                val m = infoRegex.find(text)
                if (m != null) {
                    types.add(m.groupValues[1])
                    counts.add(m.groupValues[2].ifBlank { "?" })
                } else {
                    types.add("Unknown")
                    counts.add("0")
                }
            }
        }

        val scores = doc.select(
            "span.score-10, span.score-9, span.score-8, span.score-7, span.score-6, " +
                "span.score-5, span.score-4, span.score-3, span.score-2, span.score-1, span.score-na"
        ).map { it.text().trim() }

        val items = mutableListOf<ExploreItem>()
        val total = minOf(ranks.size, titles.size, imgs.size)
        for (i in 0 until total) {
            val rank = ranks[i].let { if (it == "-") "na" else it }
            val scoreVal = if (scores.size == total) {
                scores[i]
            } else {
                scores.getOrElse(i * 2) { "N/A" }
            }
            items.add(
                ExploreItem(
                    rank = rank,
                    poster = imgs[i],
                    title = titles[i],
                    type = types.getOrElse(i) { if (media == MediaType.ANIME) "TV" else "Manga" },
                    countLabel = counts.getOrElse(i) { "?" },
                    score = scoreVal,
                    mediaType = media
                )
            )
        }

        val hasNext = doc.selectFirst("a.next") != null
        val hasPrev = doc.selectFirst("a.prev") != null && offset > 0

        return ExplorePage(
            items = items,
            offset = offset,
            hasNext = hasNext,
            hasPrev = hasPrev
        )
    }

    private fun stripMalImageResize(src: String): String {
        val rIdx = src.indexOf("/r/")
        val iIdx = src.indexOf("/images/")
        return if (rIdx >= 0 && iIdx > rIdx) {
            src.removeRange(rIdx, iIdx)
        } else {
            src
        }
    }

    companion object {
        const val SITE_URL = "https://myanimelist.net"
        private val infoRegex = Regex(
            """(TV|OVA|ONA|Movie|TVSpecial|Special|Manga|Manhwa|LightNovel|Novel|Manhua|Doujinshi|One-shot)\(?(\d+|\?)?(?:eps|vols)?\)?"""
        )
    }
}
