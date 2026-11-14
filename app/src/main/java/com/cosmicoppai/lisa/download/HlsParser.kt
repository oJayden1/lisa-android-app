package com.cosmicoppai.lisa.download

import java.net.URI
import java.nio.ByteBuffer

data class HlsKeyInfo(
    val method: String,
    val uri: String,
    val explicitIv: ByteArray?
)

data class HlsSegment(
    val index: Int,
    val uri: String,
    val initSectionUri: String?,
    val keyInfo: HlsKeyInfo?,
    val mediaSequence: Long
)

data class HlsVariantRef(
    val bandwidth: Long,
    val resolution: String?,
    val uri: String
)

sealed class ParsedHlsPlaylist {
    data class Master(val variants: List<HlsVariantRef>) : ParsedHlsPlaylist()
    data class Media(val segments: List<HlsSegment>) : ParsedHlsPlaylist()
}

object HlsParser {
    private val attrRegex = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

    fun parse(playlistText: String, baseUrl: String): ParsedHlsPlaylist {
        val lines = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = mutableListOf<HlsVariantRef>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val bw = attrs["BANDWIDTH"]?.toLongOrNull() ?: attrs["BANDWITH"]?.toLongOrNull() ?: 0L
                    val res = attrs["RESOLUTION"]
                    val nextUrl = lines.drop(i + 1).firstOrNull { !it.startsWith("#") }
                    if (!nextUrl.isNullOrBlank()) {
                        variants.add(HlsVariantRef(bw, res, resolveUrl(baseUrl, nextUrl)))
                    }
                }
                i++
            }
            return ParsedHlsPlaylist.Master(variants)
        }

        var mediaSequence = 0L
        var currentKey: HlsKeyInfo? = null
        var currentInitUri: String? = null
        val segments = mutableListOf<HlsSegment>()
        var idx = 0

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val method = attrs["METHOD"] ?: "NONE"
                    if (method.equals("NONE", ignoreCase = true)) {
                        currentKey = null
                    } else {
                        val keyUri = attrs["URI"]?.let { resolveUrl(baseUrl, it) }.orEmpty()
                        val iv = attrs["IV"]?.let { parseHexIv(it) }
                        currentKey = HlsKeyInfo(method = method, uri = keyUri, explicitIv = iv)
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    currentInitUri = attrs["URI"]?.let { resolveUrl(baseUrl, it) }
                }
                !line.startsWith("#") -> {
                    segments.add(
                        HlsSegment(
                            index = idx,
                            uri = resolveUrl(baseUrl, line),
                            initSectionUri = currentInitUri,
                            keyInfo = currentKey,
                            mediaSequence = mediaSequence + idx
                        )
                    )
                    idx++
                }
            }
        }

        return ParsedHlsPlaylist.Media(segments)
    }

    fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
            relativeOrAbsolute
        } else {
            URI(baseUrl).resolve(relativeOrAbsolute).toString()
        }
    }

    fun sequenceIv(mediaSequence: Long): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(0L)
        buf.putLong(mediaSequence)
        return buf.array()
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        attrRegex.findAll(raw).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[3].ifEmpty { match.groupValues[2] }
            map[key] = value
        }
        return map
    }

    private fun parseHexIv(raw: String): ByteArray? {
        val clean = raw.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        if (clean.length != 32) return null
        return ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
