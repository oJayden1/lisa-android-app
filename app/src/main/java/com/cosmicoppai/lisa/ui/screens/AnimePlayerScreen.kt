package com.cosmicoppai.lisa.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.cosmicoppai.lisa.data.remote.AnimepaheScraper
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.media.PreparedAnimeStream
import com.cosmicoppai.lisa.model.AnimeEpisode
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.ResolvedStreamVariant
import com.cosmicoppai.lisa.model.StreamOption
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaTopBackBar
import com.cosmicoppai.lisa.ui.theme.LisaBorder
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class AnimePlayerUiState(
    val anime: AnimeSearchItem? = null,
    val currentEpisode: AnimeEpisode? = null,
    val pageEpisodes: List<AnimeEpisode> = emptyList(),
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val streamOptionsByAudio: Map<String, List<StreamOption>> = emptyMap(),
    val selectedAudio: String = "jpn",
    val preparedStream: PreparedAnimeStream? = null,
    val selectedQualityHeight: Int? = null,
    val isLoading: Boolean = true,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class AnimePlayerViewModel @Inject constructor(
    private val repository: MediaRepository,
    val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimePlayerUiState())
    val uiState: StateFlow<AnimePlayerUiState> = _uiState.asStateFlow()

    fun initAndLoad(anime: AnimeSearchItem, episode: AnimeEpisode, page: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                anime = anime,
                currentEpisode = episode,
                currentPage = page,
                isLoading = true,
                error = null
            )
            loadEpisodePage(anime.session, page)
            loadEpisodeStream(anime.session, episode)
        }
    }

    fun loadEpisodePage(animeSession: String, page: Int) {
        viewModelScope.launch {
            runCatching {
                repository.getAnimeDetails(animeSession, page)
            }.onSuccess { details ->
                _uiState.value = _uiState.value.copy(
                    pageEpisodes = details.episodes,
                    currentPage = details.currentPage,
                    hasNextPage = details.hasNextPage,
                    hasPrevPage = details.hasPrevPage
                )
            }
        }
    }

    fun selectEpisode(episode: AnimeEpisode) {
        val anime = _uiState.value.anime ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                currentEpisode = episode,
                isLoading = true,
                error = null,
                preparedStream = null
            )
            loadEpisodeStream(anime.session, episode)
        }
    }

    fun selectAudio(audio: String) {
        val anime = _uiState.value.anime ?: return
        val episode = _uiState.value.currentEpisode ?: return
        val options = _uiState.value.streamOptionsByAudio[audio] ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedAudio = audio, isLoading = true, error = null)
            runCatching {
                repository.prepareAnimeStream(options, audio, "${anime.session}_${episode.epSession}")
            }.onSuccess { prepared ->
                _uiState.value = _uiState.value.copy(preparedStream = prepared, isLoading = false)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = err.message)
            }
        }
    }

    fun selectQuality(height: Int?) {
        _uiState.value = _uiState.value.copy(selectedQualityHeight = height)
    }

    fun playNextEpisode(): Boolean {
        val list = _uiState.value.pageEpisodes
        val current = _uiState.value.currentEpisode ?: return false
        val idx = list.indexOfFirst { it.epSession == current.epSession }
        if (idx >= 0 && idx + 1 < list.size) {
            selectEpisode(list[idx + 1])
            return true
        }
        return false
    }

    fun downloadVariant(variant: ResolvedStreamVariant) {
        val anime = _uiState.value.anime ?: return
        val ep = _uiState.value.currentEpisode ?: return
        val rawOptions = _uiState.value.streamOptionsByAudio[variant.audio].orEmpty()
        val kwikUrl = rawOptions.firstOrNull {
            it.resolution.filter { ch -> ch.isDigit() }.toIntOrNull() == variant.resolution
        }?.kwikUrl ?: ""
        viewModelScope.launch {
            repository.downloadAnimeEpisode(
                seriesName = anime.title,
                episodeName = "Episode ${ep.epNo} (${variant.resolution}p ${variant.audio.uppercase()})",
                kwikUrl = kwikUrl,
                manifestUrl = variant.manifestUrl,
                posterUrl = anime.poster
            )
            _uiState.value = _uiState.value.copy(
                message = "Started download: Ep ${ep.epNo} (${variant.resolution}p)"
            )
        }
    }

    private suspend fun loadEpisodeStream(animeSession: String, episode: AnimeEpisode) {
        runCatching {
            val map = repository.getStreamOptions(animeSession, episode.epSession)
            val chosenAudio = if (map.containsKey(_uiState.value.selectedAudio)) {
                _uiState.value.selectedAudio
            } else {
                map.keys.firstOrNull() ?: "jpn"
            }
            val options = map[chosenAudio].orEmpty()
            val prepared = if (options.isNotEmpty()) {
                repository.prepareAnimeStream(options, chosenAudio, "${animeSession}_${episode.epSession}")
            } else {
                null
            }
            Triple(map, chosenAudio, prepared)
        }.onSuccess { (map, chosenAudio, prepared) ->
            _uiState.value = _uiState.value.copy(
                streamOptionsByAudio = map,
                selectedAudio = chosenAudio,
                preparedStream = prepared,
                isLoading = false,
                error = if (prepared == null) "No playable streams returned" else null
            )
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = err.message ?: "Failed to load stream"
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

@OptIn(UnstableApi::class)
@ExperimentalLayoutApi
@Composable
fun AnimePlayerScreen(
    anime: AnimeSearchItem,
    initialEpisode: AnimeEpisode,
    initialPage: Int,
    onBack: () -> Unit,
    viewModel: AnimePlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isFullscreen by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showDownloadMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    LaunchedEffect(anime.session, initialEpisode.epSession) {
        viewModel.initAndLoad(anime, initialEpisode, initialPage)
    }

    DisposableEffect(isFullscreen) {
        val window = activity?.window
        if (activity != null && window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val trackSelector = remember(context) { DefaultTrackSelector(context) }
    val exoPlayer = remember(context) {
        val okHttpFactory = OkHttpDataSource.Factory(viewModel.okHttpClient)
            .setDefaultRequestProperties(AnimepaheScraper.KWIK_HEADERS)
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(HlsMediaSource.Factory(dataSourceFactory))
            .build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    resumePositionMs = 0L
                    viewModel.playNextEpisode()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val prepared = state.preparedStream
    LaunchedEffect(prepared?.masterManifestUri) {
        val uri = prepared?.masterManifestUri ?: return@LaunchedEffect
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (resumePositionMs > 0) {
            exoPlayer.seekTo(resumePositionMs)
        }
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(state.selectedQualityHeight) {
        val h = state.selectedQualityHeight
        trackSelector.parameters = if (h == null) {
            trackSelector.buildUponParameters().clearVideoSizeConstraints().build()
        } else {
            trackSelector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, h)
                .setMinVideoSize(0, h)
                .build()
        }
    }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!isFullscreen) {
            LisaTopBackBar(
                title = anime.title,
                subtitle = "Episode ${state.currentEpisode?.epNo ?: initialEpisode.epNo}",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        activity?.enterPictureInPictureMode(
                            PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build()
                        )
                    }) {
                        Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                    }
                    IconButton(onClick = {
                        val variantUrl = prepared?.variants?.firstOrNull()?.manifestUrl ?: return@IconButton
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(variantUrl), "application/x-mpegURL")
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "Open in External Player"))
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "External Player", tint = Color.White)
                    }
                }
            )
        }

        Box(
            modifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                        useController = true
                    }
                },
                update = { it.player = exoPlayer }
            )

            IconButton(
                onClick = { isFullscreen = !isFullscreen },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = Color.White
                )
            }
        }

        if (!isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(12.dp)
            ) {
                if (state.message != null) {
                    Text(
                        text = state.message!!,
                        color = LisaPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clickable { viewModel.clearMessage() }
                    )
                }

                when {
                    state.isLoading -> LisaLoadingView("Resolving video stream...")
                    state.error != null -> LisaErrorView(
                        message = state.error!!,
                        onRetry = { state.currentEpisode?.let { viewModel.selectEpisode(it) } }
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.streamOptionsByAudio.keys.forEach { audio ->
                        FilterChip(
                            selected = state.selectedAudio == audio,
                            onClick = {
                                resumePositionMs = exoPlayer.currentPosition
                                viewModel.selectAudio(audio)
                            },
                            label = {
                                Text(if (audio == "jpn") "Japanese" else if (audio == "eng") "English" else audio)
                            }
                        )
                    }

                    Box {
                        OutlinedButton(onClick = { showQualityMenu = true }) {
                            Text(state.selectedQualityHeight?.let { "${it}p" } ?: "Auto Quality")
                        }
                        DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Auto") },
                                onClick = {
                                    viewModel.selectQuality(null)
                                    showQualityMenu = false
                                }
                            )
                            prepared?.variants.orEmpty().forEach { v ->
                                DropdownMenuItem(
                                    text = { Text("${v.resolution}p") },
                                    onClick = {
                                        viewModel.selectQuality(v.resolution)
                                        showQualityMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        OutlinedButton(onClick = { showSpeedMenu = true }) {
                            Text("${playbackSpeed}x")
                        }
                        DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                            listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                DropdownMenuItem(
                                    text = { Text("${spd}x") },
                                    onClick = {
                                        playbackSpeed = spd
                                        showSpeedMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        Button(onClick = { showDownloadMenu = true }, enabled = prepared != null) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                        DropdownMenu(expanded = showDownloadMenu, onDismissRequest = { showDownloadMenu = false }) {
                            prepared?.variants.orEmpty().forEach { v ->
                                DropdownMenuItem(
                                    text = { Text("${v.resolution}p (${v.audio.uppercase()})") },
                                    onClick = {
                                        viewModel.downloadVariant(v)
                                        showDownloadMenu = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedButton(onClick = {
                        resumePositionMs = 0L
                        viewModel.playNextEpisode()
                    }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                        Text("Next")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Episodes (Page ${state.currentPage})",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.pageEpisodes, key = { it.epSession }) { ep ->
                        val isCurrent = ep.epSession == state.currentEpisode?.epSession
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) LisaPrimary.copy(alpha = 0.2f) else LisaSurface
                            ),
                            border = BorderStroke(1.dp, if (isCurrent) LisaPrimary else LisaBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    resumePositionMs = 0L
                                    viewModel.selectEpisode(ep)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Episode ${ep.epNo}",
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) LisaPrimary else Color.White
                                )
                                Text(text = ep.duration, style = MaterialTheme.typography.labelMedium)
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
                                    onClick = { viewModel.loadEpisodePage(anime.session, state.currentPage - 1) },
                                    enabled = state.hasPrevPage
                                ) { Text("Prev") }
                                Button(
                                    onClick = { viewModel.loadEpisodePage(anime.session, state.currentPage + 1) },
                                    enabled = state.hasNextPage
                                ) { Text("Next") }
                            }
                        }
                    }
                }
            }
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
