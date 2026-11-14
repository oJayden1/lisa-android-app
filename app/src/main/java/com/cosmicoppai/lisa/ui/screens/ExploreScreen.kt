package com.cosmicoppai.lisa.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.cosmicoppai.lisa.model.AnimeExploreCategory
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.AppMode
import com.cosmicoppai.lisa.model.ExploreItem
import com.cosmicoppai.lisa.model.MangaExploreCategory
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.model.MediaType
import com.cosmicoppai.lisa.ui.components.AppModeSwitchBar
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExploreUiState(
    val animeCategory: AnimeExploreCategory = AnimeExploreCategory.AIRING,
    val mangaCategory: MangaExploreCategory = MangaExploreCategory.BY_POPULARITY,
    val offset: Int = 0,
    val items: List<ExploreItem> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val isLoading: Boolean = false,
    val isResolvingItem: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val _appMode = MutableStateFlow(AppMode.ANIME)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { s ->
                val changed = _appMode.value != s.appMode || _uiState.value.items.isEmpty()
                _appMode.value = s.appMode
                if (changed) loadPage(0)
            }
        }
    }

    fun setMode(mode: AppMode) {
        viewModelScope.launch { repository.setAppMode(mode) }
    }

    fun selectAnimeCategory(cat: AnimeExploreCategory) {
        _uiState.value = _uiState.value.copy(animeCategory = cat)
        loadPage(0)
    }

    fun selectMangaCategory(cat: MangaExploreCategory) {
        _uiState.value = _uiState.value.copy(mangaCategory = cat)
        loadPage(0)
    }

    fun nextPage() {
        if (_uiState.value.hasNext) loadPage(_uiState.value.offset + 50)
    }

    fun prevPage() {
        if (_uiState.value.hasPrev) loadPage((_uiState.value.offset - 50).coerceAtLeast(0))
    }

    fun loadPage(offset: Int = _uiState.value.offset) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, offset = offset)
            runCatching {
                when (_appMode.value) {
                    AppMode.ANIME -> repository.getTopAnime(_uiState.value.animeCategory, offset)
                    AppMode.MANGA -> repository.getTopManga(_uiState.value.mangaCategory, offset)
                }
            }.onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    items = page.items,
                    hasNext = page.hasNext,
                    hasPrev = page.hasPrev
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = err.message ?: "Failed to load Explore"
                )
            }
        }
    }

    fun resolveItem(
        item: ExploreItem,
        onAnimeResolved: (AnimeSearchItem) -> Unit,
        onMangaResolved: (MangaSearchItem) -> Unit
    ) {
        if (_uiState.value.isResolvingItem) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResolvingItem = true, error = null)
            runCatching {
                if (item.mediaType == MediaType.ANIME) {
                    val found = repository.resolveExploreAnime(item.title)
                        ?: error("Could not find ${item.title} on AnimePahe")
                    onAnimeResolved(found)
                } else {
                    val found = repository.resolveExploreManga(item.title)
                        ?: error("Could not find ${item.title} on MangaKatana")
                    onMangaResolved(found)
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = err.message)
            }
            _uiState.value = _uiState.value.copy(isResolvingItem = false)
        }
    }
}

@Composable
fun ExploreScreen(
    onOpenAnime: (AnimeSearchItem) -> Unit,
    onOpenManga: (MangaSearchItem) -> Unit,
    viewModel: ExploreViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mode by viewModel.appMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Explore",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            AppModeSwitchBar(mode = mode, onModeChange = viewModel::setMode)
        }

        if (state.isResolvingItem) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LisaPrimary)
            Spacer(Modifier.height(6.dp))
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (mode == AppMode.ANIME) {
                items(AnimeExploreCategory.entries) { cat ->
                    FilterChip(
                        selected = state.animeCategory == cat,
                        onClick = { viewModel.selectAnimeCategory(cat) },
                        label = { Text(cat.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LisaPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = LisaPrimary
                        )
                    )
                }
            } else {
                items(MangaExploreCategory.entries) { cat ->
                    FilterChip(
                        selected = state.mangaCategory == cat,
                        onClick = { viewModel.selectMangaCategory(cat) },
                        label = { Text(cat.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LisaPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = LisaPrimary
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            state.isLoading -> LisaLoadingView("Loading Top ${mode.label}...")
            state.error != null && state.items.isEmpty() -> {
                LisaErrorView(message = state.error!!, onRetry = { viewModel.loadPage() })
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.error != null) {
                        Text(
                            text = state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 152.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(state.items, key = { "${it.rank}_${it.title}" }) { item ->
                            ExploreCard(
                                item = item,
                                onClick = {
                                    viewModel.resolveItem(item, onOpenAnime, onOpenManga)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = viewModel::prevPage,
                            enabled = state.hasPrev && !state.isLoading
                        ) { Text("Previous") }
                        Button(
                            onClick = viewModel::nextPage,
                            enabled = state.hasNext && !state.isLoading
                        ) { Text("Next") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreCard(item: ExploreItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = LisaSurface),
        border = BorderStroke(1.dp, LisaBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(LisaSurfaceVariant)
            ) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "#${item.rank}",
                        color = LisaPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (item.score.isNotBlank() && item.score != "N/A") {
                    Surface(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = LisaStarGold,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = item.score,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.title,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = LisaTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${item.type} (${item.countLabel})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = LisaTextSecondary
                )
            }
        }
    }
}
