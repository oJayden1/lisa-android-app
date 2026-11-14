package com.cosmicoppai.lisa.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.MangaChapter
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaTopBackBar
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MangaReaderUiState(
    val manga: MangaSearchItem? = null,
    val chapters: List<MangaChapter> = emptyList(),
    val ascending: Boolean = true,
    val currentChapter: MangaChapter? = null,
    val imageUrls: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class MangaReaderViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MangaReaderUiState())
    val uiState: StateFlow<MangaReaderUiState> = _uiState.asStateFlow()

    fun init(manga: MangaSearchItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(manga = manga, isLoading = true, error = null)
            runCatching {
                repository.getMangaDetails(manga.session)
            }.onSuccess { details ->
                val ordered = details.chapters.reversed()
                val first = ordered.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    chapters = ordered,
                    ascending = true,
                    currentChapter = first
                )
                if (first != null) {
                    loadChapter(first)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "No chapters found")
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = err.message)
            }
        }
    }

    fun selectChapter(chp: MangaChapter) {
        _uiState.value = _uiState.value.copy(currentChapter = chp, isLoading = true, error = null)
        viewModelScope.launch { loadChapter(chp) }
    }

    fun toggleOrder() {
        val nextAsc = !_uiState.value.ascending
        _uiState.value = _uiState.value.copy(
            ascending = nextAsc,
            chapters = _uiState.value.chapters.reversed()
        )
    }

    fun nextChapter() {
        val list = _uiState.value.chapters
        val cur = _uiState.value.currentChapter ?: return
        val idx = list.indexOfFirst { it.chpSession == cur.chpSession }
        val target = if (_uiState.value.ascending) idx + 1 else idx - 1
        if (target in list.indices) selectChapter(list[target])
    }

    fun downloadChapter(chp: MangaChapter) {
        val manga = _uiState.value.manga ?: return
        viewModelScope.launch {
            val format = repository.settings.first().preferredMangaFormat
            repository.downloadMangaChapter(manga.title, chp, format, manga.poster)
            _uiState.value = _uiState.value.copy(message = "Scheduled ${chp.chpName} (${format.name})")
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private suspend fun loadChapter(chp: MangaChapter) {
        runCatching {
            repository.getChapterImages(chp.chpSession)
        }.onSuccess { media ->
            _uiState.value = _uiState.value.copy(
                imageUrls = media.imageUrls,
                isLoading = false,
                error = if (media.imageUrls.isEmpty()) "Chapter has no images" else null
            )
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = err.message ?: "Failed loading chapter"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderScreen(
    manga: MangaSearchItem,
    onBack: () -> Unit,
    viewModel: MangaReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showChaptersSheet by remember { mutableStateOf(false) }
    var isVerticalMode by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(manga.session) {
        viewModel.init(manga)
    }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 3.5f)
        scale = newScale
        offset = if (newScale > 1f) offset + offsetChange else Offset.Zero
    }

    if (showChaptersSheet) {
        ModalBottomSheet(onDismissRequest = { showChaptersSheet = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chapters (${state.chapters.size})", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = viewModel::toggleOrder) {
                        Icon(Icons.Filled.SwapVert, contentDescription = "Toggle Sort")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.chapters, key = { it.chpSession }) { chp ->
                        val selected = chp.chpSession == state.currentChapter?.chpSession
                        Surface(
                            color = if (selected) LisaPrimary.copy(alpha = 0.2f) else LisaSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectChapter(chp)
                                    showChaptersSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chp.chpName,
                                    color = if (selected) LisaPrimary else Color.White,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.downloadChapter(chp) }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download Chapter", tint = LisaPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LisaTopBackBar(
            title = manga.title,
            subtitle = state.currentChapter?.chpName ?: "Manga Reader",
            onBack = onBack,
            actions = {
                IconButton(onClick = { isVerticalMode = !isVerticalMode }) {
                    Icon(
                        imageVector = if (isVerticalMode) Icons.Filled.ViewCarousel else Icons.Filled.SwapVert,
                        contentDescription = "Toggle Reader Mode"
                    )
                }
                if (scale > 1.05f) {
                    IconButton(onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Icon(Icons.Filled.ZoomOutMap, contentDescription = "Reset Zoom")
                    }
                }
                IconButton(onClick = { showChaptersSheet = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters")
                }
            }
        )

        if (state.message != null) {
            Text(
                text = state.message!!,
                color = LisaPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { viewModel.clearMessage() }
            )
        }

        when {
            state.isLoading -> LisaLoadingView("Loading chapter pages...")
            state.error != null -> LisaErrorView(
                message = state.error!!,
                onRetry = { state.currentChapter?.let { viewModel.selectChapter(it) } }
            )
            else -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .transformable(state = transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1.2f) 1f else 2f
                                offset = Offset.Zero
                            })
                        }
                ) {
                    if (isVerticalMode) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(state.imageUrls) { imgUrl ->
                                AsyncImage(
                                    model = imgUrl,
                                    contentDescription = "Manga Page",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Button(onClick = viewModel::nextChapter) {
                                        Text("Next Chapter")
                                    }
                                }
                            }
                        }
                    } else {
                        val pagerState = rememberPagerState(pageCount = { state.imageUrls.size })
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        ) { page ->
                            AsyncImage(
                                model = state.imageUrls[page],
                                contentDescription = "Page ${page + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
