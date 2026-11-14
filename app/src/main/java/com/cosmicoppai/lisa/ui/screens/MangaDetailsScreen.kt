package com.cosmicoppai.lisa.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.MangaDetailsResult
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaTopBackBar
import com.cosmicoppai.lisa.ui.components.MangaPosterCard
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaTextPrimary
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MangaDetailsUiState(
    val manga: MangaSearchItem? = null,
    val detail: MangaDetailsResult? = null,
    val isLoading: Boolean = true,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class MangaDetailsViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MangaDetailsUiState())
    val uiState: StateFlow<MangaDetailsUiState> = _uiState.asStateFlow()

    fun load(manga: MangaSearchItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(manga = manga, isLoading = true, error = null)
            runCatching {
                repository.getMangaDetails(manga.session)
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(detail = result, isLoading = false)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = err.message ?: "Failed to load manga details"
                )
            }
        }
    }

    fun toggleReadlist() {
        val manga = _uiState.value.manga ?: return
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            val saved = repository.toggleReadlist(manga, detail.mangaId)
            _uiState.value = _uiState.value.copy(
                detail = detail.copy(inMyList = saved),
                message = if (saved) "Added to Read List" else "Removed from Read List"
            )
        }
    }

    fun downloadAll(format: DownloadFormat) {
        val manga = _uiState.value.manga ?: return
        val chapters = _uiState.value.detail?.chapters.orEmpty()
        if (chapters.isEmpty()) return
        viewModelScope.launch {
            repository.downloadMangaBatch(
                seriesName = manga.title,
                chapters = chapters,
                format = format,
                posterUrl = manga.poster
            )
            _uiState.value = _uiState.value.copy(
                message = "Scheduled ${chapters.size} chapters (${format.name})"
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MangaDetailsScreen(
    manga: MangaSearchItem,
    onBack: () -> Unit,
    onRead: (MangaSearchItem) -> Unit,
    onOpenRecommendation: (MangaSearchItem) -> Unit,
    viewModel: MangaDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFormatPicker by remember { mutableStateOf(false) }

    LaunchedEffect(manga.session) {
        viewModel.load(manga)
    }

    if (showFormatPicker) {
        AlertDialog(
            onDismissRequest = { showFormatPicker = false },
            title = { Text("Download Manga Format") },
            text = { Text("Choose how chapters should be saved offline.") },
            confirmButton = {
                Button(onClick = {
                    showFormatPicker = false
                    viewModel.downloadAll(DownloadFormat.PDF)
                }) { Text("PDF") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showFormatPicker = false
                    viewModel.downloadAll(DownloadFormat.JPG)
                }) { Text("JPG Images") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LisaTopBackBar(
            title = manga.title,
            subtitle = state.detail?.description?.author,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = viewModel::toggleReadlist,
                    enabled = state.detail != null
                ) {
                    val saved = state.detail?.inMyList == true
                    Icon(
                        imageVector = if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "Read List",
                        tint = if (saved) LisaPrimary else LisaTextPrimary
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

        if (state.isLoading) {
            LisaLoadingView("Loading manga details...")
            return@Column
        }
        if (state.error != null) {
            LisaErrorView(message = state.error!!, onRetry = { viewModel.load(manga) })
            return@Column
        }

        val detail = state.detail
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
                        model = manga.poster,
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
                            model = manga.poster,
                            contentDescription = manga.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = manga.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            detail?.description?.author?.let { author ->
                                Text(
                                    text = "by $author",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LisaPrimary
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${detail?.chapters?.size ?: manga.totalChps} chapters",
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (manga.status.isNotBlank()) {
                                Text(
                                    text = manga.status,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LisaTextPrimary
                                )
                            }
                        }
                    }
                }
            }

            if (!detail?.description?.summary.isNullOrBlank()) {
                item {
                    Text(
                        text = detail!!.description.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LisaTextSecondary
                    )
                }
            }

            if (manga.genres.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        manga.genres.forEach { genre ->
                            AssistChip(onClick = {}, label = { Text(genre) })
                        }
                    }
                }
            }

            if (!detail?.chapters.isNullOrEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onRead(manga) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Read")
                        }
                        OutlinedButton(
                            onClick = { showFormatPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Download All")
                        }
                    }
                }
            }

            if (!detail?.recommendations.isNullOrEmpty()) {
                item {
                    Text(
                        text = "Similar Series",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(detail!!.recommendations, key = { it.session }) { rec ->
                            Box(modifier = Modifier.width(150.dp)) {
                                MangaPosterCard(
                                    item = rec,
                                    onClick = { onOpenRecommendation(rec) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
