package com.cosmicoppai.lisa.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.ui.components.LisaErrorView
import com.cosmicoppai.lisa.ui.components.LisaLoadingView
import com.cosmicoppai.lisa.ui.components.LisaTopBackBar
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurfaceVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LocalMangaReaderViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {
    var task by mutableStateOf<DownloadTaskEntity?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var localImages by mutableStateOf<List<File>>(emptyList())
        private set
    var pdfFile by mutableStateOf<File?>(null)
        private set

    fun load(taskId: Long) {
        viewModelScope.launch {
            isLoading = true
            localImages = emptyList()
            pdfFile = null
            val found = repository.getDownloadById(taskId)
            task = found
            val loc = found?.fileLocation?.let { File(it) }
            if (loc != null && loc.exists()) {
                if (loc.isDirectory) {
                    localImages = loc.listFiles { f ->
                        f.extension.equals("jpg", true) ||
                            f.extension.equals("jpeg", true) ||
                            f.extension.equals("png", true) ||
                            f.extension.equals("webp", true)
                    }?.sortedBy { it.name }.orEmpty()
                } else if (loc.extension.equals("pdf", true)) {
                    pdfFile = loc
                }
            }
            isLoading = false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalMangaReaderScreen(
    taskId: Long,
    onBack: () -> Unit,
    viewModel: LocalMangaReaderViewModel = hiltViewModel()
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
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

    val task = viewModel.task
    Column(modifier = Modifier.fillMaxSize()) {
        LisaTopBackBar(
            title = task?.seriesName ?: "Offline Manga",
            subtitle = task?.fileName,
            onBack = onBack
        )

        when {
            viewModel.isLoading -> LisaLoadingView("Opening offline chapter...")
            viewModel.localImages.isNotEmpty() -> {
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(viewModel.localImages, key = { it.absolutePath }) { img ->
                            AsyncImage(
                                model = img,
                                contentDescription = img.name,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            viewModel.pdfFile != null -> {
                PdfPagesViewer(
                    pdfFile = viewModel.pdfFile!!,
                    scale = scale,
                    offset = offset,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { viewportSize = it }
                        .transformable(state = transformState, canPan = { scale > 1.01f })
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1.2f) 1f else 2f
                                offset = Offset.Zero
                            })
                        }
                )
            }
            else -> {
                LisaErrorView("Downloaded chapter files were not found.")
            }
        }
    }
}

private class LazyPdfHolder(
    val pfd: ParcelFileDescriptor,
    val renderer: PdfRenderer
) {
    val mutex = Mutex()
    @Volatile
    var isClosed: Boolean = false
        private set

    fun closeAsync() {
        isClosed = true
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

@Composable
private fun PdfPagesViewer(
    pdfFile: File,
    scale: Float,
    offset: Offset,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val maxWidthPx = remember(context) {
        context.resources.displayMetrics.widthPixels.coerceIn(720, 1400)
    }
    var holder by remember(pdfFile) { mutableStateOf<LazyPdfHolder?>(null) }
    var pageCount by remember(pdfFile) { mutableIntStateOf(0) }
    var openError by remember(pdfFile) { mutableStateOf<String?>(null) }
    val pageCache = remember(pdfFile) { LruCache<Int, Bitmap>(6) }

    LaunchedEffect(pdfFile) {
        openError = null
        holder = null
        pageCount = 0
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try {
                    PdfRenderer(pfd)
                } catch (t: Throwable) {
                    runCatching { pfd.close() }
                    throw t
                }
                LazyPdfHolder(pfd, renderer)
            }
        }
        opened.onSuccess { pdfHolder ->
            holder = pdfHolder
            pageCount = pdfHolder.renderer.pageCount
        }.onFailure { err ->
            openError = err.message ?: "Unable to open PDF file"
        }
    }

    DisposableEffect(holder) {
        val active = holder
        onDispose {
            pageCache.evictAll()
            active?.closeAsync()
        }
    }

    when {
        openError != null -> LisaErrorView(openError!!)
        holder == null || pageCount <= 0 -> LisaLoadingView("Opening PDF...")
        else -> {
            val activeHolder = holder!!
            LazyColumn(
                modifier = modifier
                    .background(Color.Black)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    count = pageCount,
                    key = { idx -> "${pdfFile.absolutePath}_$idx" }
                ) { pageIndex ->
                    LazyPdfPageItem(
                        holder = activeHolder,
                        pageIndex = pageIndex,
                        maxWidthPx = maxWidthPx,
                        cache = pageCache
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyPdfPageItem(
    holder: LazyPdfHolder,
    pageIndex: Int,
    maxWidthPx: Int,
    cache: LruCache<Int, Bitmap>
) {
    var bitmap by remember(holder, pageIndex) { mutableStateOf(cache.get(pageIndex)) }

    LaunchedEffect(holder, pageIndex) {
        if (bitmap != null) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            cache.get(pageIndex)?.let { return@withContext it }
            holder.mutex.withLock {
                if (holder.isClosed) return@withLock null
                cache.get(pageIndex)?.let { return@withLock it }
                runCatching {
                    holder.renderer.openPage(pageIndex).use { page ->
                        val targetW = page.width.coerceAtMost(maxWidthPx).coerceAtLeast(1)
                        val targetH = (page.height * (targetW.toFloat() / page.width.coerceAtLeast(1)))
                            .toInt()
                            .coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(pageIndex, bmp)
                        bmp
                    }
                }.getOrNull()
            }
        }
        if (rendered != null) {
            bitmap = rendered
        }
    }

    val currentBmp = bitmap
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = "PDF Page ${pageIndex + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(LisaSurfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = LisaPrimary, modifier = Modifier.size(28.dp))
        }
    }
}
