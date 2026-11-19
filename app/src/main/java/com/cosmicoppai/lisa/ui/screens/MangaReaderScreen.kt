package com.cosmicoppai.lisa.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import coil.size.Size
import com.cosmicoppai.lisa.data.remote.MangaKatanaScraper
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.MangaChapter
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaTopBackBar
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaSurfaceVariant
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val fallbackServers: List<List<String>> = emptyList(),
    val hasNextChapter: Boolean = false,
    val hasPrevChapter: Boolean = false,
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

    private var chapterJob: Job? = null

    fun init(manga: MangaSearchItem, initialChapterSession: String? = null) {
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(manga = manga, isLoading = true, error = null)
            try {
                val details = repository.getMangaDetails(manga.session)
                val ordered = details.chapters.reversed()
                val target = if (!initialChapterSession.isNullOrBlank()) {
                    ordered.firstOrNull { it.chpSession == initialChapterSession } ?: ordered.firstOrNull()
                } else {
                    ordered.firstOrNull()
                }
                _uiState.value = _uiState.value.copy(
                    chapters = ordered,
                    ascending = true,
                    currentChapter = target
                )
                updateChapterNavFlags()
                if (target != null) {
                    loadChapter(target)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "No chapters found")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (err: Throwable) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = err.message)
            }
        }
    }

    fun selectChapter(chp: MangaChapter) {
        _uiState.value = _uiState.value.copy(
            currentChapter = chp,
            isLoading = true,
            error = null
        )
        updateChapterNavFlags()
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch { loadChapter(chp) }
    }

    fun toggleOrder() {
        val nextAsc = !_uiState.value.ascending
        _uiState.value = _uiState.value.copy(
            ascending = nextAsc,
            chapters = _uiState.value.chapters.reversed()
        )
        updateChapterNavFlags()
    }

    fun nextChapter() {
        val targetIdx = nextChapterIndex() ?: return
        selectChapter(_uiState.value.chapters[targetIdx])
    }

    fun prevChapter() {
        val targetIdx = prevChapterIndex() ?: return
        selectChapter(_uiState.value.chapters[targetIdx])
    }

    private fun nextChapterIndex(): Int? {
        val list = _uiState.value.chapters
        val cur = _uiState.value.currentChapter ?: return null
        val idx = list.indexOfFirst { it.chpSession == cur.chpSession }
        if (idx < 0) return null
        val target = if (_uiState.value.ascending) idx + 1 else idx - 1
        return target.takeIf { it in list.indices }
    }

    private fun prevChapterIndex(): Int? {
        val list = _uiState.value.chapters
        val cur = _uiState.value.currentChapter ?: return null
        val idx = list.indexOfFirst { it.chpSession == cur.chpSession }
        if (idx < 0) return null
        val target = if (_uiState.value.ascending) idx - 1 else idx + 1
        return target.takeIf { it in list.indices }
    }

    private fun updateChapterNavFlags() {
        _uiState.value = _uiState.value.copy(
            hasNextChapter = nextChapterIndex() != null,
            hasPrevChapter = prevChapterIndex() != null
        )
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
        try {
            val media = repository.getChapterImages(chp.chpSession)
            _uiState.value = _uiState.value.copy(
                imageUrls = media.imageUrls,
                fallbackServers = media.fallbackServers,
                isLoading = false,
                error = if (media.imageUrls.isEmpty()) "Chapter has no images" else null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (err: Throwable) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = err.message ?: "Failed loading chapter"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MangaReaderScreen(
    manga: MangaSearchItem,
    initialChapterSession: String? = null,
    onBack: () -> Unit,
    viewModel: MangaReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showChaptersSheet by remember { mutableStateOf(false) }
    var isVerticalMode by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val resolvedServerMap = remember { mutableStateMapOf<String, Int>() }
    val verticalListState = rememberLazyListState()

    LaunchedEffect(manga.session, initialChapterSession) {
        viewModel.init(manga, initialChapterSession)
    }

    LaunchedEffect(state.currentChapter?.chpSession) {
        scale = 1f
        offset = Offset.Zero
        verticalListState.scrollToItem(0)
    }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 3.5f)
        scale = newScale
        offset = if (newScale > 1.01f) {
            val maxX = ((viewportSize.width * (newScale - 1f)) / 2f).coerceAtLeast(0f)
            val maxY = ((viewportSize.height * (newScale - 1f)) / 2f).coerceAtLeast(0f)
            Offset(
                x = (offset.x + offsetChange.x).coerceIn(-maxX, maxX),
                y = (offset.y + offsetChange.y).coerceIn(-maxY, maxY)
            )
        } else {
            Offset.Zero
        }
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
                    itemsIndexed(
                        items = state.chapters,
                        key = { idx, chp -> "${chp.chpSession}_$idx" }
                    ) { _, chp ->
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
                val context = LocalContext.current
                val targetWidthPx = remember(context) {
                    context.resources.displayMetrics.widthPixels.coerceIn(720, 1440)
                }
                val chapterSession = state.currentChapter?.chpSession.orEmpty()
                val chapterReferer = chapterSession.ifBlank { "${MangaKatanaScraper.SITE_URL}/" }
                val totalPages = state.imageUrls.size
                var preferredServerIdx by remember(chapterSession) { mutableIntStateOf(0) }
                val serverFailures = remember(chapterSession) { mutableStateMapOf<Int, Int>() }
                val prefetchedUrls = remember(chapterSession, preferredServerIdx) { mutableSetOf<String>() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .onSizeChanged { viewportSize = it }
                        .transformable(state = transformState, canPan = { scale > 1.01f })
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1.2f) 1f else 2f
                                offset = Offset.Zero
                            })
                        }
                ) {
                    if (isVerticalMode) {
                        val visiblePageIndex by remember(totalPages) {
                            derivedStateOf {
                                val first = verticalListState.firstVisibleItemIndex
                                if (totalPages > 0) (first + 1).coerceIn(1, totalPages) else 0
                            }
                        }

                        LaunchedEffect(
                            chapterSession,
                            verticalListState.firstVisibleItemIndex,
                            preferredServerIdx,
                            state.imageUrls
                        ) {
                            prefetchUpcomingPages(
                                context = context,
                                imageUrls = state.imageUrls,
                                fallbackServers = state.fallbackServers,
                                startIndex = verticalListState.firstVisibleItemIndex,
                                preferredServerIdx = preferredServerIdx,
                                referer = chapterReferer,
                                targetWidthPx = targetWidthPx,
                                prefetchedUrls = prefetchedUrls
                            )
                        }

                        LazyColumn(
                            state = verticalListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentPadding = PaddingValues(bottom = 56.dp)
                        ) {
                            itemsIndexed(
                                items = state.imageUrls,
                                key = { idx, _ -> "${chapterSession}_$idx" }
                            ) { index, primaryUrl ->
                                val candidates = remember(chapterSession, index, primaryUrl, state.fallbackServers) {
                                    (listOf(primaryUrl) + state.fallbackServers.mapNotNull { it.getOrNull(index) }).distinct()
                                }
                                MangaPageItem(
                                    pageKey = "${chapterSession}_$index",
                                    pageNumber = index + 1,
                                    totalPages = totalPages,
                                    candidateUrls = candidates,
                                    referer = chapterReferer,
                                    isFitMode = false,
                                    preferredServerIdx = preferredServerIdx,
                                    targetWidthPx = targetWidthPx,
                                    resolvedServerMap = resolvedServerMap,
                                    onServerFailed = { failedIdx ->
                                        serverFailures[failedIdx] = (serverFailures[failedIdx] ?: 0) + 1
                                    },
                                    onServerSucceeded = { pageNum, succeededIdx ->
                                        if (succeededIdx > preferredServerIdx &&
                                            ((serverFailures[preferredServerIdx] ?: 0) >= 2 || pageNum == 1)
                                        ) {
                                            preferredServerIdx = succeededIdx
                                        }
                                    }
                                )
                            }
                            item(key = "${chapterSession}_nav_footer") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                                ) {
                                    if (state.hasPrevChapter) {
                                        OutlinedButton(onClick = viewModel::prevChapter) {
                                            Text("Prev Chapter")
                                        }
                                    }
                                    if (state.hasNextChapter) {
                                        Button(onClick = viewModel::nextChapter) {
                                            Text("Next Chapter")
                                        }
                                    }
                                }
                            }
                        }

                        if (totalPages > 0) {
                            PageProgressPill(
                                currentPage = visiblePageIndex,
                                totalPages = totalPages,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            )
                        }
                    } else {
                        val pagerState = rememberPagerState(
                            initialPage = 0,
                            pageCount = { totalPages }
                        )
                        LaunchedEffect(chapterSession) {
                            pagerState.scrollToPage(0)
                        }
                        LaunchedEffect(
                            chapterSession,
                            pagerState.currentPage,
                            preferredServerIdx,
                            state.imageUrls
                        ) {
                            prefetchUpcomingPages(
                                context = context,
                                imageUrls = state.imageUrls,
                                fallbackServers = state.fallbackServers,
                                startIndex = pagerState.currentPage,
                                preferredServerIdx = preferredServerIdx,
                                referer = chapterReferer,
                                targetWidthPx = targetWidthPx,
                                prefetchedUrls = prefetchedUrls
                            )
                        }

                        HorizontalPager(
                            state = pagerState,
                            key = { idx -> "${chapterSession}_pager_$idx" },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        ) { page ->
                            val primaryUrl = state.imageUrls[page]
                            val candidates = remember(chapterSession, page, primaryUrl, state.fallbackServers) {
                                (listOf(primaryUrl) + state.fallbackServers.mapNotNull { it.getOrNull(page) }).distinct()
                            }
                            MangaPageItem(
                                pageKey = "${chapterSession}_$page",
                                pageNumber = page + 1,
                                totalPages = totalPages,
                                candidateUrls = candidates,
                                referer = chapterReferer,
                                isFitMode = true,
                                preferredServerIdx = preferredServerIdx,
                                targetWidthPx = targetWidthPx,
                                resolvedServerMap = resolvedServerMap,
                                onServerFailed = { failedIdx ->
                                    serverFailures[failedIdx] = (serverFailures[failedIdx] ?: 0) + 1
                                },
                                onServerSucceeded = { pageNum, succeededIdx ->
                                    if (succeededIdx > preferredServerIdx &&
                                        ((serverFailures[preferredServerIdx] ?: 0) >= 2 || pageNum == 1)
                                    ) {
                                        preferredServerIdx = succeededIdx
                                    }
                                }
                            )
                        }

                        if (totalPages > 0) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (state.hasPrevChapter && pagerState.currentPage == 0) {
                                    OutlinedButton(onClick = viewModel::prevChapter) {
                                        Text("Prev Ch.")
                                    }
                                }
                                PageProgressPill(
                                    currentPage = pagerState.currentPage + 1,
                                    totalPages = totalPages
                                )
                                if (state.hasNextChapter && pagerState.currentPage >= totalPages - 1) {
                                    Button(onClick = viewModel::nextChapter) {
                                        Text("Next Ch.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun prefetchUpcomingPages(
    context: android.content.Context,
    imageUrls: List<String>,
    fallbackServers: List<List<String>>,
    startIndex: Int,
    preferredServerIdx: Int,
    referer: String,
    targetWidthPx: Int,
    prefetchedUrls: MutableSet<String>
) {
    if (imageUrls.isEmpty()) return
    val safeStart = startIndex.coerceIn(0, imageUrls.lastIndex)
    val safeEnd = (safeStart + 5).coerceAtMost(imageUrls.lastIndex)
    for (idx in safeStart..safeEnd) {
        val candidates = (listOf(imageUrls[idx]) + fallbackServers.mapNotNull { it.getOrNull(idx) }).distinct()
        val url = candidates.getOrElse(preferredServerIdx) { candidates.firstOrNull().orEmpty() }
        if (url.isNotBlank() && prefetchedUrls.add(url)) {
            context.imageLoader.enqueue(
                buildMangaImageRequest(
                    context = context,
                    url = url,
                    referer = referer,
                    targetWidthPx = targetWidthPx
                )
            )
        }
    }
}

private fun buildMangaImageRequest(
    context: android.content.Context,
    url: String,
    referer: String,
    targetWidthPx: Int,
    retryNonce: Int = 0
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(url)
        .addHeader("Referer", referer)
        .allowHardware(false)
        .size(Size(targetWidthPx, Dimension.Undefined))
        .precision(Precision.INEXACT)
    if (retryNonce > 0) {
        builder.setParameter("retry", retryNonce)
    }
    return builder.build()
}

@Composable
private fun PageProgressPill(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            text = "Page $currentPage / $totalPages",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun MangaPageItem(
    pageKey: String,
    pageNumber: Int,
    totalPages: Int,
    candidateUrls: List<String>,
    referer: String,
    isFitMode: Boolean,
    preferredServerIdx: Int,
    targetWidthPx: Int,
    resolvedServerMap: MutableMap<String, Int>,
    onServerFailed: (Int) -> Unit,
    onServerSucceeded: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    var serverIdx by remember(pageKey) {
        mutableIntStateOf(
            (resolvedServerMap[pageKey] ?: preferredServerIdx)
                .coerceIn(0, (candidateUrls.size - 1).coerceAtLeast(0))
        )
    }
    var retryNonce by remember(pageKey) { mutableIntStateOf(0) }

    LaunchedEffect(preferredServerIdx) {
        if (resolvedServerMap[pageKey] == null &&
            preferredServerIdx in candidateUrls.indices &&
            preferredServerIdx > serverIdx
        ) {
            serverIdx = preferredServerIdx
        }
    }

    val activeUrl = candidateUrls.getOrElse(serverIdx) { candidateUrls.firstOrNull().orEmpty() }

    val request = remember(activeUrl, retryNonce, referer, targetWidthPx) {
        buildMangaImageRequest(
            context = context,
            url = activeUrl,
            referer = referer,
            targetWidthPx = targetWidthPx,
            retryNonce = retryNonce
        )
    }

    val containerModifier = if (isFitMode) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = "Page $pageNumber",
        contentScale = if (isFitMode) ContentScale.Fit else ContentScale.FillWidth,
        modifier = containerModifier,
        onSuccess = {
            resolvedServerMap[pageKey] = serverIdx
            onServerSucceeded(pageNumber, serverIdx)
        },
        onError = {
            onServerFailed(serverIdx)
            if (serverIdx + 1 < candidateUrls.size) {
                serverIdx += 1
            }
        },
        loading = {
            Box(
                modifier = if (isFitMode) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .background(LisaSurfaceVariant.copy(alpha = 0.35f))
                },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LisaPrimary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Loading page $pageNumber / $totalPages...",
                        color = LisaTextSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        error = {
            if (serverIdx + 1 < candidateUrls.size) {
                // Switching to backup CDN server automatically
                Box(
                    modifier = if (isFitMode) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(440.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = LisaPrimary, modifier = Modifier.size(28.dp))
                }
            } else {
                Box(
                    modifier = if (isFitMode) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .background(LisaSurfaceVariant.copy(alpha = 0.4f))
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Failed to load page $pageNumber",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            serverIdx = 0
                            retryNonce += 1
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Retry Page")
                        }
                    }
                }
            }
        },
        success = {
            SubcomposeAsyncImageContent()
        }
    )
}
