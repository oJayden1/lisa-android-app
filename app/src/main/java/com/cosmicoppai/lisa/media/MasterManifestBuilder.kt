package com.cosmicoppai.lisa.media

import android.content.Context
import android.net.Uri
import com.cosmicoppai.lisa.data.remote.AnimepaheScraper
import com.cosmicoppai.lisa.model.ResolvedStreamVariant
import com.cosmicoppai.lisa.model.StreamOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

data class PreparedAnimeStream(
    val masterManifestUri: Uri,
    val variants: List<ResolvedStreamVariant>
)

@Singleton
class MasterManifestBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val animepaheScraper: AnimepaheScraper
) {
    suspend fun prepareStream(
        streamOptions: List<StreamOption>,
        audio: String,
        cacheKey: String
    ): PreparedAnimeStream = coroutineScope {
        val settled = streamOptions.map { option ->
            async {
                runCatching {
                    val res = option.resolution.filter { it.isDigit() }.toIntOrNull() ?: 720
                    val kwik = animepaheScraper.getHlsPlaylist(option.kwikUrl)
                    ResolvedStreamVariant(
                        resolution = res,
                        audio = audio,
                        manifestUrl = kwik.manifestUrl,
                        fileName = kwik.fileName
                    )
                }
            }
        }.awaitAll()

        val variants = settled.mapNotNull { it.getOrNull() }.sortedByDescending { it.resolution }
        if (variants.isEmpty()) {
            val firstError = settled.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw firstError ?: IllegalStateException("No playable stream variants found")
        }

        val manifestContent = buildMasterManifestText(variants)
        val manifestDir = File(context.cacheDir, "manifests").apply { mkdirs() }
        val manifestFile = File(manifestDir, "${sanitizeKey(cacheKey)}_$audio.m3u8")
        manifestFile.writeText(manifestContent)

        PreparedAnimeStream(
            masterManifestUri = Uri.fromFile(manifestFile),
            variants = variants
        )
    }

    fun buildMasterManifestText(variants: List<ResolvedStreamVariant>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        variants.forEach { variant ->
            val height = variant.resolution.coerceAtLeast(360)
            val width = ceil(16.0 / 9.0 * height).toInt()
            val bandwidth = ((width * height) / 1_000_000.0 * 30 * 24 * 1000).toInt().coerceAtLeast(500_000)
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=${width}x${height}\n")
            sb.append("${variant.manifestUrl}\n")
        }
        return sb.toString()
    }

    private fun sanitizeKey(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
}
