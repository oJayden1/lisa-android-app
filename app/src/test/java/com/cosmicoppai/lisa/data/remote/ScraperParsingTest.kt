package com.cosmicoppai.lisa.data.remote

import com.cosmicoppai.lisa.data.local.UserPreferences
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.MediaType
import com.cosmicoppai.lisa.ui.navigation.decodeNavJson
import com.cosmicoppai.lisa.ui.navigation.encodeNavJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperParsingTest {

    @Test
    fun `parseTopHtml parses MAL anime rows and strips resized HMAC query`() {
        val malScraper = MalScraper(OkHttpClient())
        val html = """
            <html><body>
            <table>
              <tr>
                <td><span class="rank1">1</span></td>
                <td><img width="50" height="70" data-src="https://cdn.myanimelist.net/r/50x70/images/anime/123.jpg?s=deadbeef1234" /></td>
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

    @Test
    fun `parseChapterImagesHtml handles multiline arrays, backup servers, and DOM fallback`() {
        val scraper = MangaKatanaScraper(OkHttpClient())
        val multiLineHtml = """
            <html><body>
            <select name="chapter_select"><option selected="selected">Chapter 1: Romance Dawn</option></select>
            <script>
              var ytaw = [
                'https://backup.example.com/1.jpg',
                'https://backup.example.com/2.jpg'
              ];
              var thzq = [
                '//cdn1.example.com/1.jpg',
                'https://cdn1.example.com/2.jpg',
              ];
            </script>
            <div id="imgs">
              <img data-src="https://dom.example.com/1.jpg" />
              <img src="https://dom.example.com/2.jpg" />
            </div>
            </body></html>
        """.trimIndent()

        val chp = scraper.parseChapterImagesHtml(multiLineHtml, "https://mangakatana.com/manga/one-piece.49/c1")
        assertEquals(listOf("https://cdn1.example.com/1.jpg", "https://cdn1.example.com/2.jpg"), chp.imageUrls)
        assertEquals(2, chp.fallbackServers.size)
        assertEquals(listOf("https://backup.example.com/1.jpg", "https://backup.example.com/2.jpg"), chp.fallbackServers[0])
        assertEquals(listOf("https://dom.example.com/1.jpg", "https://dom.example.com/2.jpg"), chp.fallbackServers[1])
    }

    @Test
    fun `parseMangaDetailHtml extracts Similar Series posters from data-src, data-lazy-src, and src`() {
        val scraper = MangaKatanaScraper(OkHttpClient())
        val detailHtml = """
            <html><body>
              <div class="chapters"><table><tbody>
                <tr><td><div class="chapter"><a href="/manga/one-piece.49/c1">Chapter 1</a></div></td></tr>
              </tbody></table></div>
              <div id="hot_book">
                <div class="widget">
                  <div class="widget-title"><span>Similar Series</span></div>
                  <div class="item">
                    <div class="wrap_img"><img src="//cdn.mk/img1.jpg" /></div>
                    <div class="text"><h3><a href="/manga/black-clover.100">Black Clover</a></h3><div class="chapter">Ch.370</div></div>
                  </div>
                  <div class="item">
                    <div class="wrap_img"><img data-lazy-src="https://cdn.mk/img2.jpg" src="data:image/gif;base64,R0lGOD" /></div>
                    <div class="text"><h3><a href="https://mangakatana.com/manga/saiyuki.200">Saiyuki</a></h3><div class="chapter">Ch.50</div></div>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val detail = scraper.parseMangaDetailHtml(detailHtml, "https://mangakatana.com/manga/one-piece.49")
        assertEquals(1, detail.chapters.size)
        assertEquals("https://mangakatana.com/manga/one-piece.49/c1", detail.chapters[0].chpSession)
        assertEquals(2, detail.recommendations.size)
        assertEquals("https://cdn.mk/img1.jpg", detail.recommendations[0].poster)
        assertEquals("https://cdn.mk/img2.jpg", detail.recommendations[1].poster)
        assertEquals("https://mangakatana.com/manga/black-clover.100", detail.recommendations[0].session)
    }

    @Test
    fun `DynamicCookieJar matches cookies stored across redirected mirror hosts`() {
        val jar = DynamicCookieJar()
        jar.setCookiesForUrl(
            "https://animepahe.pw/",
            listOf(StoredCookie("__ddg2_", "token123", ".animepahe.pw"))
        )
        val pwCookies = jar.loadForRequest("https://animepahe.pw/api?m=search&q=frieren".toHttpUrl())
        assertEquals(1, pwCookies.size)
        assertEquals("__ddg2_", pwCookies[0].name)
        assertEquals("token123", pwCookies[0].value)
    }

    @Test
    fun `parseRecommendationsHtml extracts clean title and structured metadata`() {
        val html = """
            <html><body>
              <div class="col-2">
                <a href="/anime/abc-123-def">
                  <img data-src="//i.animepahe.pw/posters/bluebox.th.jpg" src="data:image/gif;base64,R0lGOD" />
                </a>
              </div>
              <div class="col-9">
                <h5><a href="/anime/abc-123-def">Blue Box</a></h5>
                <div><a href="/anime/type/tv">TV</a> - 25 Episodes (Finished Airing)</div>
                <div><a href="/anime/season/fall-2024">Fall 2024</a></div>
              </div>
            </body></html>
        """.trimIndent()

        val recs = AnimepaheScraper.parseRecommendationsHtml(html)
        assertEquals(1, recs.size)
        val item = recs[0]
        assertEquals("Blue Box", item.title)
        assertEquals("abc-123-def", item.session)
        assertEquals("TV", item.type)
        assertEquals("25", item.noOfEpisodes)
        assertEquals("Finished Airing", item.status)
        assertEquals("Fall", item.season)
        assertEquals("2024", item.year)
        assertEquals("https://i.animepahe.pw/posters/bluebox.th.jpg", item.poster)
    }

    @Test
    fun `parseAnimeDescriptionHtml extracts poster from anime-poster or og_image`() {
        val htmlWithPosterDiv = """
            <html><head><meta property="og:image" content="https://i.animepahe.pw/posters/fallback.jpg" /></head>
            <body>
              <a class="fa-link" href="/a/9876">link</a>
              <div class="anime-poster"><img data-src="https://i.animepahe.pw/posters/hero.jpg" src="" /></div>
              <div class="anime-synopsis">"A great series."</div>
              <div class="anime-info"><p>Studio: Telecom</p><p>Season: Fall 2024</p></div>
            </body></html>
        """.trimIndent()

        val desc1 = AnimepaheScraper.parseAnimeDescriptionHtml(htmlWithPosterDiv)
        assertEquals(9876, desc1.animeId)
        assertEquals("https://i.animepahe.pw/posters/hero.jpg", desc1.poster)
        assertEquals("Telecom", desc1.studio)

        val htmlWithOgFallback = """
            <html><head><meta property="og:image" content="//i.animepahe.pw/posters/oghero.jpg" /></head>
            <body>
              <a class="fa-link" href="/a/555">link</a>
              <div class="anime-synopsis">Synopsis</div>
            </body></html>
        """.trimIndent()

        val desc2 = AnimepaheScraper.parseAnimeDescriptionHtml(htmlWithOgFallback)
        assertEquals(555, desc2.animeId)
        assertEquals("https://i.animepahe.pw/posters/oghero.jpg", desc2.poster)
    }

    @Test
    fun `parseSearchHtml parses MangaKatana direct-redirect detail page even when title contains hyphen`() {
        val scraper = MangaKatanaScraper(OkHttpClient())
        val redirectDetailHtml = """
            <html>
              <head>
                <title>Re:Zero - Starting Life in Another World - MangaKatana</title>
                <meta property="og:url" content="https://mangakatana.com/manga/rezero.123" />
              </head>
              <body>
                <div class="cover"><img data-src="https://cdn.mk/rezero.jpg" /></div>
                <div class="info">
                  <h1 class="heading">Re:Zero - Starting Life in Another World</h1>
                  <ul class="meta d-table">
                    <li><div class="status">ongoing</div></li>
                    <li><div class="genres"><a>Fantasy</a><a>Drama</a></div></li>
                  </ul>
                </div>
                <div class="chapter"><a href="/manga/rezero.123/c42">Chapter 42</a></div>
              </body>
            </html>
        """.trimIndent()

        val page = scraper.parseSearchHtml(redirectDetailHtml)
        assertEquals(1, page.results.size)
        val item = page.results[0]
        assertEquals("Re:Zero - Starting Life in Another World", item.title)
        assertEquals("42", item.totalChps)
        assertEquals("Ongoing", item.status)
        assertEquals(listOf("Fantasy", "Drama"), item.genres)
        assertEquals("https://cdn.mk/rezero.jpg", item.poster)
        assertEquals("https://mangakatana.com/manga/rezero.123", item.session)
    }

    @Test
    fun `encodeNavJson and decodeNavJson round-trip special characters and reject malformed input`() {
        val original = AnimeSearchItem(
            animeId = 77,
            title = "Mob Psycho 100% \"Reigen\" / Special?a=1&b=2#top",
            type = "TV",
            noOfEpisodes = "12",
            status = "Finished Airing",
            season = "Summer",
            year = "2016",
            score = 8.5,
            poster = "https://i.animepahe.pw/posters/mob%20100.jpg?x=1&y=2",
            session = "mob-session-100%"
        )

        val encoded = encodeNavJson(original)
        assertTrue(!encoded.contains('%') && !encoded.contains('/') && !encoded.contains('?'))
        val decoded = decodeNavJson<AnimeSearchItem>(encoded)
        assertEquals(original, decoded)
        assertNull(decodeNavJson<AnimeSearchItem>("!!!not-valid-base64!!!"))
        assertNull(decodeNavJson<AnimeSearchItem>(""))
    }

    @Test
    fun `UserPreferences normalizeBaseUrl validates and normalizes mirror URLs`() {
        assertEquals("https://animepahe.pw", UserPreferences.normalizeBaseUrl("animepahe.pw"))
        assertEquals("https://animepahe.com", UserPreferences.normalizeBaseUrl("https://animepahe.com/"))
        assertEquals("https://animepahe.org", UserPreferences.normalizeBaseUrl("  https://animepahe.org/// "))
        assertNull(UserPreferences.normalizeBaseUrl(""))
        assertNull(UserPreferences.normalizeBaseUrl("   "))
        assertNull(UserPreferences.normalizeBaseUrl("not a url"))
        assertNull(UserPreferences.normalizeBaseUrl("https://localhost"))
    }

    @Test
    fun `parseMangaDetailHtml handles lazy attributes, placeholders, CSS bg, noscript, relative paths, and cover fallback`() {
        val scraper = MangaKatanaScraper(OkHttpClient())
        val detailHtml = """
            <html>
              <head>
                <meta property="og:image" content="imgs/cover/og_berserk.jpg" />
              </head>
              <body>
                <div class="cover"><img src="imgs/cover/berserk.jpg" /></div>
                <div class="chapters"><table><tbody>
                  <tr><td><div class="chapter"><a href="manga/berserk.1/c1">Chapter 1</a></div></td></tr>
                </tbody></table></div>
                <div id="hot_book">
                  <div class="widget">
                    <div class="widget-title"><span>Similar Series</span></div>
                    <div class="item">
                      <a class="wrap_img" href="manga/claymore.10">
                        <img src="/static/blank.gif" data-lazy="imgs/cover/claymore.jpg" />
                      </a>
                      <div class="text"><h3><a href="manga/claymore.10">Claymore</a></h3><div class="chapter">Ch.155</div></div>
                    </div>
                    <div class="item">
                      <div class="wrap_img" data-bg="//i2.mangakatana.com/cover/vagabond.jpg">
                        <img src="/assets/loading.gif" />
                      </div>
                      <div class="text"><h3><a href="/manga/vagabond.20">Vagabond</a></h3><div class="chapter">Ch.327</div></div>
                    </div>
                    <div class="item">
                      <div class="wrap_img" style="background-image: url('https://i3.mangakatana.com/cover/vinland.jpg')"></div>
                      <div class="text"><h3><a href="/manga/vinland-saga.30">Vinland Saga</a></h3></div>
                    </div>
                    <div class="item">
                      <div class="wrap_img">
                        <img src="data:image/gif;base64,R0lGOD" />
                        <noscript><img srcset="https://i1.mangakatana.com/cover/kingdom.jpg 1x, https://i1.mangakatana.com/cover/kingdom@2x.jpg 2x" /></noscript>
                      </div>
                      <div class="text"><a href="/manga/kingdom.40">Kingdom</a></div>
                    </div>
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()

        val detail = scraper.parseMangaDetailHtml(detailHtml, "https://mangakatana.com/manga/berserk.1")
        assertEquals("https://mangakatana.com/imgs/cover/berserk.jpg", detail.description.poster)
        assertEquals(1, detail.chapters.size)
        assertEquals("https://mangakatana.com/manga/berserk.1/c1", detail.chapters[0].chpSession)
        assertEquals(4, detail.recommendations.size)
        assertEquals("https://mangakatana.com/imgs/cover/claymore.jpg", detail.recommendations[0].poster)
        assertEquals("https://mangakatana.com/manga/claymore.10", detail.recommendations[0].session)
        assertEquals("https://i2.mangakatana.com/cover/vagabond.jpg", detail.recommendations[1].poster)
        assertEquals("https://i3.mangakatana.com/cover/vinland.jpg", detail.recommendations[2].poster)
        assertEquals("https://i1.mangakatana.com/cover/kingdom.jpg", detail.recommendations[3].poster)
        assertEquals("https://mangakatana.com/manga/kingdom.40", detail.recommendations[3].session)

        // Verify og:image fallback when div.cover img is absent
        val ogOnlyHtml = """
            <html>
              <head><meta property="og:image" content="//cdn.mk/fallback_og.jpg" /></head>
              <body><div class="summary"><p>Fallback cover summary</p></div></body>
            </html>
        """.trimIndent()
        val ogDetail = scraper.parseMangaDetailHtml(ogOnlyHtml, "https://mangakatana.com/manga/test.2")
        assertEquals("https://cdn.mk/fallback_og.jpg", ogDetail.description.poster)
    }

    @Test
    fun `CloudflareCookieResolver classifies normal HTML vs challenge and interactive captcha pages`() {
        val normalHtml = "<html><head><title>AnimePahe</title></head><body><div class='anime-list'>Episodes</div></body></html>"
        val jsChallenge = "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>"
        val turnstileHtml = "<html><head><title>Just a moment...</title></head><body><div class='cf-turnstile'>Verify you are human</div></body></html>"

        assertTrue(!CloudflareCookieResolver.isChallengeInterstitial(normalHtml))
        assertTrue(!CloudflareCookieResolver.hasInteractiveCaptchaMarker(normalHtml))
        assertTrue(CloudflareCookieResolver.isChallengeInterstitial(jsChallenge))
        assertTrue(!CloudflareCookieResolver.hasInteractiveCaptchaMarker(jsChallenge))
        assertTrue(CloudflareCookieResolver.isChallengeInterstitial(turnstileHtml))
        assertTrue(CloudflareCookieResolver.hasInteractiveCaptchaMarker(turnstileHtml))
    }
}
