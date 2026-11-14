package com.cosmicoppai.lisa.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.AnimeDescription
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.StreamOption
import com.cosmicoppai.lisa.ui.components.AnimePosterCard
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaTopBackBar
import com.cosmicoppai.lisa.ui.theme.LisaBackground
import com.cosmicoppai.lisa.ui.theme.LisaBorder
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaStarGold
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaSurfaceVariant
import com.cosmicoppai.lisa.ui.theme.LisaTextPrimary
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnimeDetailsUiState(
    val anime: AnimeSearchItem? = null,
    val description: AnimeDescription? = null,
    val episodes: List<AnimeEpisode> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val inMyList: Boolean = false,
    val recommendations: List<AnimeSearchItem> = emptyList(),
    val isLoading: Boolean = true,
    val isBatchScheduling: Boolean = false,
    val singleDownloadOptions: Pair<AnimeEpisode, Map<String, List<StreamOption>>>? = null,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class AnimeDetailsViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimeDetailsUiState())
    val uiState: StateFlow<AnimeDetailsUiState> = _uiState.asStateFlow()

    fun load(anime: AnimeSearchItem, page: Int = 1) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                anime = anime,
                isLoading = true,
                error = null
            )
            runCatching {
                val pageData = repository.getAnimeDetails(anime.session, page)
                val recs = if (page == 1 && _uiState.value.recommendations.isEmpty()) {
                    runCatching { repository.getAnimeRecommendations(anime.session) }.getOrDefault(emptyList())
                } else {
                    _uiState.value.recommendations
                }
                pageData to recs
            }.onSuccess { (pageData, recs) ->
                val desc = pageData.description ?: _uiState.value.description
                val saved = if (desc != null && desc.animeId > 0) {
                    pageData.inMyList || _uiState.value.inMyList
                } else {
                    _uiState.value.inMyList
                }
                _uiState.value = _uiState.value.copy(
                    description = desc,
                    episodes = pageData.episodes,
                    currentPage = pageData.currentPage,
                    totalPages = pageData.totalPages,
                    hasNextPage = pageData.hasNextPage,
                    hasPrevPage = pageData.hasPrevPage,
                    inMyList = saved,
                    recommendations = recs,
                    isLoading = false
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = err.message ?: "Failed to load anime details"
                )
            }
        }
    }

    fun toggleWatchlist() {
        val anime = _uiState.value.anime ?: return
        viewModelScope.launch {
            val saved = repository.toggleWatchlist(anime, _uiState.value.description)
            _uiState.value = _uiState.value.copy(
                inMyList = saved,
                message = if (saved) "Added to Watchlist" else "Removed from Watchlist"
            )
        }
    }

    fun openSingleEpisodeDownloadPicker(episode: AnimeEpisode) {
        val anime = _uiState.value.anime ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "Fetching download qualities...")
            runCatching {
                repository.getStreamOptions(anime.session, episode.epSession)
            }.onSuccess { map ->
                _uiState.value = _uiState.value.copy(
                    singleDownloadOptions = episode to map,
                    message = null
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(message = err.message ?: "Failed to fetch qualities")
            }
        }
    }

    fun dismissSingleDownloadPicker() {
        _uiState.value = _uiState.value.copy(singleDownloadOptions = null)
    }

    fun startSingleEpisodeDownload(episode: AnimeEpisode, option: StreamOption) {
        val anime = _uiState.value.anime ?: return
        dismissSingleDownloadPicker()
        viewModelScope.launch {
            repository.downloadAnimeEpisode(
                seriesName = anime.title,
                episodeName = "Episode ${episode.epNo} (${option.resolution}p ${option.audio.uppercase()})",
                kwikUrl = option.kwikUrl,
                posterUrl = anime.poster
            )
            _uiState.value = _uiState.value.copy(message = "Started download: Ep ${episode.epNo}")
        }
    }

    fun downloadAllOnPage() {
        val anime = _uiState.value.anime ?: return
        val eps = _uiState.value.episodes
        if (eps.isEmpty() || _uiState.value.isBatchScheduling) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBatchScheduling = true, message = "Scheduling ${eps.size} episodes...")
            val prefs = repository.settings.first()
            runCatching {
                repository.downloadAnimeBatch(
                    seriesName = anime.title,
                    animeSession = anime.session,
                    episodes = eps,
                    preferredAudio = prefs.preferredAudio,
                    preferredQuality = prefs.preferredQuality,
                    posterUrl = anime.poster
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isBatchScheduling = false,
                    message = "Scheduled ${eps.size} episodes for download"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isBatchScheduling = false,
                    message = err.message ?: "Batch download failed"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimeDetailsScreen(
    anime: AnimeSearchItem,
    onBack: () -> Unit,
    onPlayEpisode: (AnimeSearchItem, AnimeEpisode, Int) -> Unit,
    onOpenRecommendation: (AnimeSearchItem) -> Unit,
    viewModel: AnimeDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(anime.session) {
        viewModel.load(anime, 1)
    }

    state.singleDownloadOptions?.let { (episode, optionsMap) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSingleDownloadPicker,
            title = { Text("Download Episode ${episode.epNo}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    optionsMap.forEach { (audio, list) ->
                        Text(
                            text = if (audio == "jpn") "Japanese Audio" else "English Audio ($audio)",
                            style = MaterialTheme.typography.labelLarge,
                            color = LisaPrimary
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { opt ->
                                OutlinedButton(
                                    onClick = { viewModel.startSingleEpisodeDownload(episode, opt) }
                                ) {
                                    Text("${opt.resolution}p")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSingleDownloadPicker) { Text("Close") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LisaTopBackBar(
            title = anime.title,
            subtitle = state.description?.engName,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = viewModel::toggleWatchlist,
                    enabled = (state.description?.animeId ?: 0) > 0
                ) {
                    Icon(
                        imageVector = if (state.inMyList) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "My List",
                        tint = if (state.inMyList) LisaPrimary else LisaTextPrimary
                    )
                }
            }
        )

        if (state.message != null) {
            Surface(
                color = LisaPrimary.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { viewModel.clearMessage() },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = state.message!!,
                    color = LisaPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (state.isLoading && state.episodes.isEmpty()) {
            LisaLoadingView("Loading anime details...")
            return@Column
        }
        if (state.error != null && state.episodes.isEmpty()) {
            LisaErrorView(message = state.error!!, onRetry = { viewModel.load(anime, state.currentPage) })
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LisaSurface)
                ) {
                    AsyncImage(
                        model = anime.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur(20.dp)
                            .alpha(0.25f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AsyncImage(
                            model = anime.poster,
                            contentDescription = anime.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = anime.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            state.description?.studio?.let { studio ->
                                Text(
                                    text = "by $studio",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LisaPrimary
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                InfoPill(anime.type.ifBlank { state.description?.type ?: "TV" })
                                val st = state.description?.status?.ifBlank { anime.status } ?: anime.status
                                if (st.isNotBlank()) InfoPill(st)
                                if (anime.score > 0) {
                                    Surface(
                                        color = LisaSurfaceVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = LisaStarGold,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = anime.score.toString(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = LisaTextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val epLabel = if (anime.noOfEpisodes == "?") "Ongoing" else "Episodes: ${anime.noOfEpisodes}"
                            Text(text = epLabel, style = MaterialTheme.typography.labelMedium)
                            state.description?.aired?.let {
                                Text(text = it, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (!state.description?.synopsis.isNullOrBlank()) {
                item {
                    Text(
                        text = state.description!!.synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LisaTextSecondary
                    )
                }
            }

            val extLinks = state.description?.externalLinks.orEmpty()
            if (extLinks.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        extLinks.forEach { (name, url) ->
                            AssistChip(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                                label = { Text(name) }
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Episodes (Page ${state.currentPage}/${state.totalPages.coerceAtLeast(1)})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.episodes.isNotEmpty()) {
                        Button(
                            onClick = viewModel::downloadAllOnPage,
                            enabled = !state.isBatchScheduling
                        ) {
                            if (state.isBatchScheduling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Download All")
                        }
                    }
                }
            }

            items(state.episodes, key = { it.epSession }) { ep ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayEpisode(anime, ep, state.currentPage) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LisaSurface),
                    border = BorderStroke(1.dp, LisaBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(116.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LisaSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ep.snapshot,
                                contentDescription = "Episode ${ep.epNo}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Episode ${ep.epNo}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (ep.duration.isNotBlank()) {
                                Text(
                                    text = ep.duration,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.openSingleEpisodeDownloadPicker(ep) }) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Download Episode",
                                tint = LisaPrimary
                            )
                        }
                    }
                }
            }

            if (state.hasPrevPage || state.hasNextPage) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.load(anime, state.currentPage - 1) },
                            enabled = state.hasPrevPage && !state.isLoading
                        ) { Text("Prev Page") }
                        Button(
                            onClick = { viewModel.load(anime, state.currentPage + 1) },
                            enabled = state.hasNextPage && !state.isLoading
                        ) { Text("Next Page") }
                    }
                }
            }

            item {
                TabRow(selectedTabIndex = selectedTab, containerColor = LisaSurface) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Recommendations") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Trailer") })
                }
                Spacer(Modifier.height(10.dp))
                if (selectedTab == 0) {
                    if (state.recommendations.isEmpty()) {
                        Text("No recommendations available", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.recommendations, key = { it.session }) { rec ->
                                Box(modifier = Modifier.width(150.dp)) {
                                    AnimePosterCard(item = rec, onClick = { onOpenRecommendation(rec) })
                                }
                            }
                        }
                    }
                } else {
                    val yt = state.description?.youtubeUrl
                    if (yt.isNullOrBlank()) {
                        Text("No trailer available", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(yt)))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Tv, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Watch Trailer on YouTube")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(color = LisaSurfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = LisaTextPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
