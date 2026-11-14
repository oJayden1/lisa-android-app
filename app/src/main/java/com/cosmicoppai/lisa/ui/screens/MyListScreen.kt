package com.cosmicoppai.lisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cosmicoppai.lisa.data.local.entity.ReadlistEntity
import com.cosmicoppai.lisa.data.local.entity.WatchlistEntity
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.AppMode
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.components.AnimePosterCard
import com.cosmicoppai.lisa.ui.components.AppModeSwitchBar
import com.cosmicoppai.lisa.ui.components.MangaPosterCard
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MyListViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val json: Json
) : ViewModel() {

    val appMode: StateFlow<AppMode> = repository.settings
        .map { it.appMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppMode.ANIME)

    val watchlist: StateFlow<List<WatchlistEntity>> = repository.observeWatchlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readlist: StateFlow<List<ReadlistEntity>> = repository.observeReadlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var isResolvingSession by mutableStateOf(false)
        private set

    fun setMode(mode: AppMode) {
        viewModelScope.launch { repository.setAppMode(mode) }
    }

    fun openWatchlistItem(entity: WatchlistEntity, onOpenAnime: (AnimeSearchItem) -> Unit) {
        if (isResolvingSession) return
        viewModelScope.launch {
            isResolvingSession = true
            val resolvedSession = runCatching {
                repository.resolveSessionById(entity.animeId)
            }.getOrDefault(entity.session)
            isResolvingSession = false
            onOpenAnime(
                AnimeSearchItem(
                    title = entity.jpName,
                    noOfEpisodes = entity.noOfEpisodes,
                    type = entity.type,
                    status = entity.status,
                    season = entity.season,
                    year = entity.year,
                    score = entity.score,
                    session = resolvedSession.ifBlank { entity.session },
                    poster = entity.poster,
                    animeId = entity.animeId
                )
            )
        }
    }

    fun toMangaSearchItem(entity: ReadlistEntity): MangaSearchItem {
        val genres = runCatching { json.decodeFromString<List<String>>(entity.genresJson) }
            .getOrDefault(emptyList())
        return MangaSearchItem(
            title = entity.title,
            totalChps = entity.totalChps,
            genres = genres,
            poster = entity.poster,
            status = entity.status,
            session = entity.session
        )
    }
}

@Composable
fun MyListScreen(
    onOpenAnime: (AnimeSearchItem) -> Unit,
    onOpenManga: (MangaSearchItem) -> Unit,
    viewModel: MyListViewModel = hiltViewModel()
) {
    val mode by viewModel.appMode.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val readlist by viewModel.readlist.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My List",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            AppModeSwitchBar(mode = mode, onModeChange = viewModel::setMode)
        }

        if (viewModel.isResolvingSession) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LisaPrimary)
            Spacer(Modifier.height(6.dp))
        }

        if (mode == AppMode.ANIME) {
            if (watchlist.isEmpty()) {
                EmptyListState("Your Anime My List is Empty")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 152.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(watchlist, key = { it.animeId }) { entry ->
                        val cardItem = AnimeSearchItem(
                            title = entry.jpName,
                            noOfEpisodes = entry.noOfEpisodes,
                            type = entry.type,
                            status = entry.status,
                            season = entry.season,
                            year = entry.year,
                            score = entry.score,
                            session = entry.session,
                            poster = entry.poster,
                            animeId = entry.animeId
                        )
                        AnimePosterCard(
                            item = cardItem,
                            onClick = { viewModel.openWatchlistItem(entry, onOpenAnime) }
                        )
                    }
                }
            }
        } else {
            if (readlist.isEmpty()) {
                EmptyListState("Your Manga My List is Empty")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 152.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(readlist, key = { it.mangaId }) { entry ->
                        val item = viewModel.toMangaSearchItem(entry)
                        MangaPosterCard(
                            item = item,
                            onClick = { onOpenManga(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyListState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.SentimentDissatisfied,
            contentDescription = null,
            tint = LisaTextSecondary,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(text = message, style = MaterialTheme.typography.titleMedium, color = LisaTextSecondary)
    }
}
