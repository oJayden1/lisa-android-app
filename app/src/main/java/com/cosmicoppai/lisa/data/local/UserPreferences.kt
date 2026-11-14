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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "lisa_preferences")

data class LisaSettings(
    val appMode: AppMode = AppMode.ANIME,
    val preferredAudio: String = "jpn",
    val preferredQuality: Int = 1080,
    val preferredMangaFormat: DownloadFormat = DownloadFormat.JPG,
    val animepaheBaseUrl: String = "https://animepahe.com"
)

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
        LisaSettings(
            appMode = prefs[Keys.APP_MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
                ?: AppMode.ANIME,
            preferredAudio = prefs[Keys.PREFERRED_AUDIO] ?: "jpn",
            preferredQuality = prefs[Keys.PREFERRED_QUALITY]?.toIntOrNull() ?: 1080,
            preferredMangaFormat = prefs[Keys.MANGA_FORMAT]?.let {
                runCatching { DownloadFormat.valueOf(it) }.getOrNull()
            } ?: DownloadFormat.JPG,
            animepaheBaseUrl = prefs[Keys.ANIMEPAHE_BASE_URL] ?: "https://animepahe.com"
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
        context.dataStore.edit { it[Keys.ANIMEPAHE_BASE_URL] = url.trimEnd('/') }
    }
}
