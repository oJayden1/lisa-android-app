package com.cosmicoppai.lisa.data.remote

data class KwikStreamResult(
    val fileName: String,
    val manifestUrl: String
)

object KwikJsUnpacker {
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val whitespaceRegex = Regex("""\s+""")
    private val packedRegex = Regex("""returnp\}\('(.*?)',(\d*),(\d*),'(.*?)'\.split""")
    private val titleRegex = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
    private val streamRegex = Regex("""https://[^\s'"\\)]+(?:owo|uwu)\.m3u8(?:\?[^\s'"\\)]*)?""")
    private val fallbackM3u8Regex = Regex("""https://[^\s'"\\)]+\.m3u8(?:\?[^\s'"\\)]*)?""")

    fun extractFromHtml(html: String): KwikStreamResult {
        val minified = whitespaceRegex.replace(html, "").trim()
        val title = titleRegex.find(html)?.groupValues?.get(1)?.trim()
            ?: titleRegex.find(minified)?.groupValues?.get(1)?.trim()
            ?: "AnimePahe_Episode.mp4"

        val matches = packedRegex.findAll(minified).toList()
        val manifestUrl = if (matches.isNotEmpty()) {
            matches.asReversed().firstNotNullOfOrNull { match ->
                val p = match.groupValues[1]
                val a = match.groupValues[2].toIntOrNull() ?: return@firstNotNullOfOrNull null
                val c = match.groupValues[3].toIntOrNull() ?: return@firstNotNullOfOrNull null
                val k = match.groupValues[4]
                if (a <= 0 || a > DIGITS.length) return@firstNotNullOfOrNull null

                val unpacked = unpack(p, a, c, k)
                streamRegex.find(unpacked)?.value ?: fallbackM3u8Regex.find(unpacked)?.value
            }
                ?: streamRegex.find(html)?.value
                ?: fallbackM3u8Regex.find(html)?.value
                ?: error("Unable to extract .m3u8 manifest URL from unpacked Kwik script")
        } else {
            streamRegex.find(html)?.value
                ?: fallbackM3u8Regex.find(html)?.value
                ?: throw IllegalArgumentException("Unable to locate packed Kwik script in response")
        }

        return KwikStreamResult(
            fileName = title,
            manifestUrl = manifestUrl
        )
    }

    fun unpack(payload: String, base: Int, countInit: Int, keywordsRaw: String): String {
        val keywords = keywordsRaw.split("|")
        val dict = LinkedHashMap<String, String>()
        var c = countInit
        while (c > 0) {
            c -= 1
            val key = int2base(c, base)
            val replacement = keywords.getOrNull(c).orEmpty()
            dict[key] = replacement.ifEmpty { key }
        }
        var result = payload
        for ((token, value) in dict) {
            result = Regex("""\b${Regex.escape(token)}\b""").replace(result, Regex.escapeReplacement(value))
        }
        return result
    }

    fun int2base(value: Int, base: Int): String {
        if (value == 0) return DIGITS[0].toString()
        val negative = value < 0
        var num = kotlin.math.abs(value)
        val sb = StringBuilder()
        while (num > 0) {
            sb.append(DIGITS[num % base])
            num /= base
        }
        if (negative) sb.append('-')
        return sb.reverse().toString()
    }
}
