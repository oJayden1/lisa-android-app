package com.cosmicoppai.lisa.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsParserTest {

    @Test
    fun `parse extracts master playlist variants`() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            720/uwu.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            https://cdn.kwik.cx/1080/uwu.m3u8
        """.trimIndent()

        val parsed = HlsParser.parse(master, "https://cdn.kwik.cx/master.m3u8")
        assertTrue(parsed is ParsedHlsPlaylist.Master)
        val variants = (parsed as ParsedHlsPlaylist.Master).variants
        assertEquals(2, variants.size)
        assertEquals("https://cdn.kwik.cx/720/uwu.m3u8", variants[0].uri)
        assertEquals("https://cdn.kwik.cx/1080/uwu.m3u8", variants[1].uri)
        assertEquals(3000000L, variants[1].bandwidth)
    }

    @Test
    fun `parse extracts AES-128 media segments and relative URLs`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-KEY:METHOD=AES-128,URI="mon.key",IV=0x0000000000000000000000000000000A
            #EXTINF:5.0,
            seg0.ts
            #EXTINF:5.0,
            seg1.ts
        """.trimIndent()

        val parsed = HlsParser.parse(media, "https://cdn.kwik.cx/hls/uwu.m3u8")
        assertTrue(parsed is ParsedHlsPlaylist.Media)
        val segments = (parsed as ParsedHlsPlaylist.Media).segments
        assertEquals(2, segments.size)
        assertEquals("https://cdn.kwik.cx/hls/seg0.ts", segments[0].uri)
        assertEquals("https://cdn.kwik.cx/hls/mon.key", segments[0].keyInfo?.uri)
        assertNotNull(segments[0].keyInfo?.explicitIv)
        assertEquals(10L, segments[0].mediaSequence)
        assertEquals(11L, segments[1].mediaSequence)
    }

    @Test
    fun `decryptAes128 decrypts CBC payload cleanly`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = HlsParser.sequenceIv(42L)
        val plain = "Hello MPEG-TS segment payload!".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)

        val decrypted = Mp4Remuxer.decryptAes128(encrypted, key, iv)
        assertArrayEquals(plain, decrypted)
    }
}
