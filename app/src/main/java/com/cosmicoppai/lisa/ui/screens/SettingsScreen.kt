package com.cosmicoppai.lisa.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cosmicoppai.lisa.BuildConfig
import com.cosmicoppai.lisa.R
import com.cosmicoppai.lisa.data.local.LisaSettings
import com.cosmicoppai.lisa.data.local.UserPreferences
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.ui.theme.LisaBorder
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    val settings: StateFlow<LisaSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LisaSettings())

    private val _statusMsg = MutableStateFlow<String?>(null)
    val statusMsg: StateFlow<String?> = _statusMsg.asStateFlow()

    fun setAudio(audio: String) {
        viewModelScope.launch { repository.setPreferredAudio(audio) }
    }

    fun setQuality(quality: Int) {
        viewModelScope.launch { repository.setPreferredQuality(quality) }
    }

    fun setMangaFormat(format: DownloadFormat) {
        viewModelScope.launch { repository.setPreferredMangaFormat(format) }
    }

    fun setBaseUrl(url: String) {
        val normalized = UserPreferences.normalizeBaseUrl(url)
        if (normalized == null) {
            _statusMsg.value = "Invalid URL. Enter a valid domain (e.g. https://animepahe.pw)"
            return
        }
        viewModelScope.launch {
            repository.setAnimepaheBaseUrl(normalized)
            _statusMsg.value = "Updated AnimePahe base URL to $normalized"
        }
    }

    fun verifyAccess() {
        _statusMsg.value = "Opening AnimePahe verification..."
        repository.requestAnimepaheVerification(settings.value.animepaheBaseUrl) { passed ->
            _statusMsg.value = if (passed) {
                "AnimePahe access verified and cookies saved"
            } else {
                "Verification cancelled"
            }
        }
    }

    fun refreshCookies() {
        viewModelScope.launch {
            _statusMsg.value = "Refreshing AnimePahe session cookies..."
            runCatching {
                repository.refreshCookies(settings.value.animepaheBaseUrl)
            }.onSuccess {
                _statusMsg.value = "Session cookies refreshed"
            }.onFailure {
                _statusMsg.value = it.message ?: "Cookie refresh failed"
            }
        }
    }

    fun clearCookies() {
        viewModelScope.launch {
            repository.clearCookies()
            _statusMsg.value = "Cleared cached cookies"
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMsg.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mirrorInput by remember(settings.animepaheBaseUrl) {
        mutableStateOf(settings.animepaheBaseUrl)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.home_screen_logo),
                    contentDescription = "LiSA Logo",
                    modifier = Modifier.height(100.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LisaTextSecondary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Cosmicoppai/LiSA"))
                        )
                    }
                }) {
                    Text("Upstream LiSA GitHub")
                }
            }
        }

        if (statusMsg != null) {
            item {
                Text(
                    text = statusMsg!!,
                    color = LisaPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        item {
            SettingsCard("Preferred Anime Audio") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.preferredAudio == "jpn",
                        onClick = { viewModel.setAudio("jpn") },
                        label = { Text("Japanese (Sub)") }
                    )
                    FilterChip(
                        selected = settings.preferredAudio == "eng",
                        onClick = { viewModel.setAudio("eng") },
                        label = { Text("English (Dub)") }
                    )
                }
            }
        }

        item {
            SettingsCard("Default Download Resolution") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1080, 720, 360).forEach { q ->
                        FilterChip(
                            selected = settings.preferredQuality == q,
                            onClick = { viewModel.setQuality(q) },
                            label = { Text("${q}p") }
                        )
                    }
                }
            }
        }

        item {
            SettingsCard("Default Manga Download Format") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.preferredMangaFormat == DownloadFormat.JPG,
                        onClick = { viewModel.setMangaFormat(DownloadFormat.JPG) },
                        label = { Text("JPG Images") }
                    )
                    FilterChip(
                        selected = settings.preferredMangaFormat == DownloadFormat.PDF,
                        onClick = { viewModel.setMangaFormat(DownloadFormat.PDF) },
                        label = { Text("PDF Document") }
                    )
                }
            }
        }

        item {
            SettingsCard("AnimePahe Domain & Session Cookies") {
                OutlinedTextField(
                    value = mirrorInput,
                    onValueChange = { mirrorInput = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.setBaseUrl(mirrorInput) }) {
                        Text("Save URL")
                    }
                    OutlinedButton(onClick = {
                        mirrorInput = "https://animepahe.pw"
                        viewModel.setBaseUrl("https://animepahe.pw")
                    }) {
                        Text(".pw")
                    }
                    OutlinedButton(onClick = {
                        mirrorInput = "https://animepahe.org"
                        viewModel.setBaseUrl("https://animepahe.org")
                    }) {
                        Text(".org")
                    }
                    OutlinedButton(onClick = {
                        mirrorInput = "https://animepahe.com"
                        viewModel.setBaseUrl("https://animepahe.com")
                    }) {
                        Text(".com")
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = viewModel::verifyAccess) {
                        Text("Verify Access")
                    }
                    OutlinedButton(onClick = viewModel::refreshCookies) {
                        Text("Refresh Cookies")
                    }
                    OutlinedButton(onClick = viewModel::clearCookies) {
                        Text("Clear Cookies")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = LisaSurface),
        border = BorderStroke(1.dp, LisaBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
