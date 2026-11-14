package com.cosmicoppai.lisa.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cosmicoppai.lisa.R
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.AppMode
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.components.AnimePosterCard
import com.cosmicoppai.lisa.ui.components.AppModeSwitchBar
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaNotFoundView
import com.cosmicoppai.lisa.ui.components.MangaPosterCard
import com.cosmicoppai.lisa.ui.theme.LisaBorder
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaTextPrimary
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val takingLonger: Boolean = false,
    val animeResults: List<AnimeSearchItem> = emptyList(),
    val mangaResults: List<MangaSearchItem> = emptyList(),
    val hasSearched: Boolean = false,
    val error: String? = null
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val appMode: StateFlow<AppMode> = MutableStateFlow(AppMode.ANIME).also { flow ->
        viewModelScope.launch {
            repository.settings.collect { flow.value = it.appMode }
        }
    }

    init {
        combine(
            queryFlow.debounce(450).distinctUntilChanged(),
            repository.settings
        ) { q, settings -> q.trim() to settings.appMode }
            .onEach { (trimmed, mode) ->
                if (trimmed.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        takingLonger = false,
                        animeResults = emptyList(),
                        mangaResults = emptyList(),
                        hasSearched = false,
                        error = null
                    )
                } else {
                    performSearch(trimmed, mode)
                }
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChanged(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
        queryFlow.value = value
    }

    fun setMode(mode: AppMode) {
        viewModelScope.launch { repository.setAppMode(mode) }
    }

    fun retry() {
        val currentQuery = _uiState.value.query.trim()
        if (currentQuery.isNotEmpty()) {
            viewModelScope.launch {
                performSearch(currentQuery, appMode.value)
            }
        }
    }

    private suspend fun performSearch(query: String, mode: AppMode) {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            takingLonger = false,
            error = null
        )

        val slowTimer = viewModelScope.launch {
            delay(5_000L)
            if (_uiState.value.isLoading) {
                _uiState.value = _uiState.value.copy(takingLonger = true)
            }
        }

        runCatching {
            when (mode) {
                AppMode.ANIME -> repository.searchAnime(query) to emptyList<MangaSearchItem>()
                AppMode.MANGA -> emptyList<AnimeSearchItem>() to repository.searchManga(query).results
            }
        }.onSuccess { (anime, manga) ->
            slowTimer.cancel()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                takingLonger = false,
                animeResults = anime,
                mangaResults = manga,
                hasSearched = true,
                error = null
            )
        }.onFailure { err ->
            slowTimer.cancel()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                takingLonger = false,
                hasSearched = true,
                error = err.message ?: "Search failed"
            )
        }
    }
}

@Composable
fun SearchScreen(
    onOpenAnime: (AnimeSearchItem) -> Unit,
    onOpenManga: (MangaSearchItem) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
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
                text = "LiSA",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            AppModeSwitchBar(mode = mode, onModeChange = viewModel::setMode)
        }

        if (!state.hasSearched && state.query.isBlank()) {
            Spacer(Modifier.height(24.dp))
            Image(
                painter = painterResource(id = R.drawable.home_screen_logo),
                contentDescription = "LiSA Logo",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Search ${mode.label}...", color = LisaTextSecondary)
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = LisaTextSecondary)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = LisaTextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LisaSurface,
                unfocusedContainerColor = LisaSurface,
                focusedBorderColor = LisaPrimary,
                unfocusedBorderColor = LisaBorder,
                focusedTextColor = LisaTextPrimary,
                unfocusedTextColor = LisaTextPrimary
            )
        )

        Spacer(Modifier.height(14.dp))

        when {
            state.isLoading -> {
                val msg = if (state.takingLonger) {
                    "It's taking longer than usual..."
                } else {
                    "Searching ${mode.label}..."
                }
                LisaLoadingView(msg)
            }
            state.error != null -> {
                LisaErrorView(message = state.error!!, onRetry = viewModel::retry)
            }
            state.hasSearched && mode == AppMode.ANIME && state.animeResults.isEmpty() -> {
                LisaNotFoundView(
                    title = "Anime Not Found",
                    subtitle = "The result you're looking for does not seem to exist"
                )
            }
            state.hasSearched && mode == AppMode.MANGA && state.mangaResults.isEmpty() -> {
                LisaNotFoundView(
                    title = "Manga Not Found",
                    subtitle = "The result you're looking for does not seem to exist"
                )
            }
            mode == AppMode.ANIME && state.animeResults.isNotEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 152.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.animeResults, key = { it.session }) { item ->
                        AnimePosterCard(item = item, onClick = { onOpenAnime(item) })
                    }
                }
            }
            mode == AppMode.MANGA && state.mangaResults.isNotEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 152.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.mangaResults, key = { it.session }) { item ->
                        MangaPosterCard(item = item, onClick = { onOpenManga(item) })
                    }
                }
            }
        }
    }
}
