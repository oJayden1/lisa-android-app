package com.cosmicoppai.lisa.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KwikJsUnpackerTest {

    @Test
    fun `int2base converts numbers across bases accurately`() {
        assertEquals("0", KwikJsUnpacker.int2base(0, 36))
        assertEquals("a", KwikJsUnpacker.int2base(10, 36))
        assertEquals("10", KwikJsUnpacker.int2base(36, 36))
        assertEquals("Z", KwikJsUnpacker.int2base(61, 62))
    }

    @Test
    fun `extractFromHtml unpacks Dean Edwards script and finds uwu m3u8`() {
        // Packed representation of: var source='https://cdn.example.com/hls/1080/uwu.m3u8';
        val payload = "0 1='2://3.4.5/6/7/8.9';"
        val keywords = "var|source|https|cdn|example|com|hls|1080|uwu|m3u8"
        val html = """
            <html>
            <head><title>AnimePahe_Bakuman_-_01_1080p.mp4</title></head>
            <body>
            <script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('$payload',10,10,'$keywords'.split('|'),0,{}))
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = KwikJsUnpacker.extractFromHtml(html)
        assertEquals("AnimePahe_Bakuman_-_01_1080p.mp4", result.fileName)
        assertEquals("https://cdn.example.com/hls/1080/uwu.m3u8", result.manifestUrl)
        assertTrue(result.manifestUrl.endsWith("uwu.m3u8"))
    }
}
