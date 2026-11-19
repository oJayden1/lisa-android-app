package com.cosmicoppai.lisa.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cosmicoppai.lisa.model.AppMode
import com.cosmicoppai.lisa.model.DownloadFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "lisa_preferences")

data class LisaSettings(
    val appMode: AppMode = AppMode.ANIME,
    val preferredAudio: String = "jpn",
    val preferredQuality: Int = 1080,
    val preferredMangaFormat: DownloadFormat = DownloadFormat.JPG,
    val animepaheBaseUrl: String = DEFAULT_ANIMEPAHE_URL
) {
    companion object {
        const val DEFAULT_ANIMEPAHE_URL = "https://animepahe.pw"
    }
}

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val PREFERRED_AUDIO = stringPreferencesKey("preferred_audio")
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val MANGA_FORMAT = stringPreferencesKey("manga_format")
        val ANIMEPAHE_BASE_URL = stringPreferencesKey("animepahe_base_url")
    }

    val settings: Flow<LisaSettings> = context.dataStore.data.map { prefs ->
        val rawBaseUrl = prefs[Keys.ANIMEPAHE_BASE_URL]?.trimEnd('/')
        val migratedBaseUrl = when {
            rawBaseUrl.isNullOrBlank() -> LisaSettings.DEFAULT_ANIMEPAHE_URL
            rawBaseUrl.equals("https://animepahe.ru", ignoreCase = true) ||
                rawBaseUrl.equals("http://animepahe.ru", ignoreCase = true) -> LisaSettings.DEFAULT_ANIMEPAHE_URL
            else -> normalizeBaseUrl(rawBaseUrl) ?: LisaSettings.DEFAULT_ANIMEPAHE_URL
        }
        LisaSettings(
            appMode = prefs[Keys.APP_MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
                ?: AppMode.ANIME,
            preferredAudio = prefs[Keys.PREFERRED_AUDIO] ?: "jpn",
            preferredQuality = prefs[Keys.PREFERRED_QUALITY]?.toIntOrNull() ?: 1080,
            preferredMangaFormat = prefs[Keys.MANGA_FORMAT]?.let {
                runCatching { DownloadFormat.valueOf(it) }.getOrNull()
            } ?: DownloadFormat.JPG,
            animepaheBaseUrl = migratedBaseUrl
        )
    }

    suspend fun setAppMode(mode: AppMode) {
        context.dataStore.edit { it[Keys.APP_MODE] = mode.name }
    }

    suspend fun setPreferredAudio(audio: String) {
        context.dataStore.edit { it[Keys.PREFERRED_AUDIO] = audio }
    }

    suspend fun setPreferredQuality(quality: Int) {
        context.dataStore.edit { it[Keys.PREFERRED_QUALITY] = quality.toString() }
    }

    suspend fun setPreferredMangaFormat(format: DownloadFormat) {
        context.dataStore.edit { it[Keys.MANGA_FORMAT] = format.name }
    }

    suspend fun setAnimepaheBaseUrl(url: String) {
        val normalized = normalizeBaseUrl(url) ?: return
        context.dataStore.edit { it[Keys.ANIMEPAHE_BASE_URL] = normalized }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            if (parsed.host.isBlank() || !parsed.host.contains('.')) return null
            val portSuffix = if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
            return "${parsed.scheme}://${parsed.host}$portSuffix"
        }
    }
}
