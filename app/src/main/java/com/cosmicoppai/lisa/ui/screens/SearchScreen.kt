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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.getValue
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
import com.cosmicoppai.lisa.data.remote.InteractiveCaptchaException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val takingLonger: Boolean = false,
    val animeResults: List<AnimeSearchItem> = emptyList(),
    val mangaResults: List<MangaSearchItem> = emptyList(),
    val hasSearched: Boolean = false,
    val error: String? = null,
    val requiresVerification: Boolean = false,
    val verificationSiteUrl: String = ""
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val retryNonce = MutableStateFlow(0)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val distinctAppModeFlow = repository.settings
        .map { it.appMode }
        .distinctUntilChanged()

    val appMode: StateFlow<AppMode> = distinctAppModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppMode.ANIME)

    init {
        viewModelScope.launch {
            combine(
                queryFlow.debounce(450).map { it.trim() }.distinctUntilChanged(),
                distinctAppModeFlow,
                retryNonce
            ) { trimmed, mode, _ -> trimmed to mode }
                .collectLatest { (trimmed, mode) ->
                    if (trimmed.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            takingLonger = false,
                            animeResults = emptyList(),
                            mangaResults = emptyList(),
                            hasSearched = false,
                            error = null,
                            requiresVerification = false,
                            verificationSiteUrl = ""
                        )
                    } else {
                        performSearch(trimmed, mode)
                    }
                }
        }
    }

    fun onQueryChanged(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
        queryFlow.value = value
    }

    fun setMode(mode: AppMode) {
        viewModelScope.launch { repository.setAppMode(mode) }
    }

    fun retry() {
        if (_uiState.value.query.trim().isNotEmpty()) {
            retryNonce.value += 1
        }
    }

    fun verifyAndRetry() {
        val siteUrl = _uiState.value.verificationSiteUrl.takeIf { it.isNotBlank() }
        repository.requestAnimepaheVerification(siteUrl) { passed ->
            if (passed) {
                _uiState.value = _uiState.value.copy(
                    error = null,
                    requiresVerification = false,
                    verificationSiteUrl = ""
                )
                retry()
            }
        }
    }

    private suspend fun performSearch(query: String, mode: AppMode) = coroutineScope {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            takingLonger = false,
            error = null,
            requiresVerification = false,
            verificationSiteUrl = ""
        )

        val slowTimer = launch {
            delay(5_000L)
            if (_uiState.value.isLoading) {
                _uiState.value = _uiState.value.copy(takingLonger = true)
            }
        }

        try {
            val (anime, manga) = when (mode) {
                AppMode.ANIME -> repository.searchAnime(query) to emptyList<MangaSearchItem>()
                AppMode.MANGA -> emptyList<AnimeSearchItem>() to repository.searchManga(query).results
            }
            slowTimer.cancel()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                takingLonger = false,
                animeResults = anime,
                mangaResults = manga,
                hasSearched = true,
                error = null,
                requiresVerification = false,
                verificationSiteUrl = ""
            )
        } catch (e: CancellationException) {
            slowTimer.cancel()
            throw e
        } catch (err: Throwable) {
            slowTimer.cancel()
            val captcha = err as? InteractiveCaptchaException
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                takingLonger = false,
                hasSearched = true,
                error = err.message ?: "Search failed",
                requiresVerification = captcha != null,
                verificationSiteUrl = captcha?.siteUrl.orEmpty()
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
                LisaErrorView(
                    message = state.error!!,
                    onRetry = viewModel::retry,
                    secondaryActionLabel = if (state.requiresVerification) "Verify & Retry" else null,
                    onSecondaryAction = if (state.requiresVerification) viewModel::verifyAndRetry else null
                )
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
                    itemsIndexed(
                        items = state.animeResults,
                        key = { idx, item -> "anime_${item.session}_$idx" }
                    ) { _, item ->
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
                    itemsIndexed(
                        items = state.mangaResults,
                        key = { idx, item -> "manga_${item.session}_$idx" }
                    ) { _, item ->
                        MangaPosterCard(item = item, onClick = { onOpenManga(item) })
                    }
                }
            }
        }
    }
}
