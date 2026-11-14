package com.cosmicoppai.lisa.data.remote

import android.content.Context
import com.cosmicoppai.lisa.model.MediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperParsingTest {

    @Test
    fun `parseTopHtml parses MAL anime rows`() {
        val malScraper = MalScraper(OkHttpClient())
        val html = """
            <html><body>
            <table>
              <tr>
                <td><span class="rank1">1</span></td>
                <td><img width="50" height="70" data-src="https://cdn.myanimelist.net/r/50x70/images/anime/123.jpg" /></td>
                <td>
                  <h3 class="anime_ranking_h3">Frieren</h3>
                  <div class="information">TV (28 eps)</div>
                </td>
                <td><span class="score-9">9.32</span></td>
              </tr>
            </table>
            <a class="next" href="?limit=50">Next</a>
            </body></html>
        """.trimIndent()

        val page = malScraper.parseTopHtml(html, MediaType.ANIME, 0)
        assertEquals(1, page.items.size)
        val item = page.items[0]
        assertEquals("1", item.rank)
        assertEquals("Frieren", item.title)
        assertEquals("https://cdn.myanimelist.net/images/anime/123.jpg", item.poster)
        assertEquals("TV", item.type)
        assertEquals("28", item.countLabel)
        assertEquals("9.32", item.score)
        assertTrue(page.hasNext)
    }

    @Test
    fun `parseSearchHtml parses MangaKatana list and chapter images`() {
        val scraper = MangaKatanaScraper(OkHttpClient())
        val searchHtml = """
            <html><head><title>Search Manga - MangaKatana</title></head>
            <body>
              <div id="book_list">
                <div class="item">
                  <div class="media">
                    <div class="wrap_img"><a href="https://mangakatana.com/manga/doraemon.10"><img src="https://img/dora.jpg"/></a></div>
                    <div class="status">Completed</div>
                  </div>
                  <div class="text">
                    <a href="https://mangakatana.com/manga/doraemon.10">Doraemon</a>
                    <span> - 450 chapters</span>
                  </div>
                  <div class="genres"><a>Comedy</a><a>Sci-Fi</a></div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val res = scraper.parseSearchHtml(searchHtml)
        assertEquals(1, res.results.size)
        assertEquals("Doraemon", res.results[0].title)
        assertEquals("450", res.results[0].totalChps)
        assertEquals(listOf("Comedy", "Sci-Fi"), res.results[0].genres)

        val chpHtml = """
            <html><body>
            <select name="chapter_select"><option selected="selected">Chapter 1</option></select>
            <script>var thzq=['https://img/1.jpg','https://img/2.jpg',];</script>
            </body></html>
        """.trimIndent()
        val chp = scraper.parseChapterImagesHtml(chpHtml, "https://mangakatana.com/manga/doraemon.10/c1")
        assertEquals(listOf("https://img/1.jpg", "https://img/2.jpg"), chp.imageUrls)
        assertEquals("Chapter 1", chp.chapterTitle)
    }
}
