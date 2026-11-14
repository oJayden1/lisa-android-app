package com.cosmicoppai.lisa.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            val found = repository.getDownloadById(taskId)
            task = found
            val loc = found?.fileLocation?.let { File(it) }
            if (loc != null && loc.exists()) {
                if (loc.isDirectory) {
                    localImages = loc.listFiles { f -> f.extension.equals("jpg", true) || f.extension.equals("png", true) }
                        ?.sortedBy { it.name }
                        .orEmpty()
                } else if (loc.extension.equals("pdf", true)) {
                    pdfFile = loc
                }
            }
            isLoading = false
        }
    }
}

@Composable
fun LocalMangaReaderScreen(
    taskId: Long,
    onBack: () -> Unit,
    viewModel: LocalMangaReaderViewModel = hiltViewModel()
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 3.5f)
        scale = newScale
        offset = if (newScale > 1f) offset + offsetChange else Offset.Zero
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
                        .transformable(transformState)
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
                        .transformable(transformState)
                )
            }
            else -> {
                LisaErrorView("Downloaded chapter files were not found.")
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
    var bitmaps by remember(pdfFile) { mutableStateOf<List<Bitmap>>(emptyList()) }

    DisposableEffect(pdfFile) {
        onDispose {
            bitmaps.forEach { it.recycle() }
        }
    }

    LaunchedEffect(pdfFile) {
        bitmaps = withContext(Dispatchers.IO) {
            val result = mutableListOf<Bitmap>()
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val targetW = page.width.coerceAtMost(1400)
                            val targetH = (page.height * (targetW.toFloat() / page.width)).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result.add(bmp)
                        }
                    }
                }
            }
            result
        }
    }

    if (bitmaps.isEmpty()) {
        LisaLoadingView("Rendering PDF pages...")
    } else {
        LazyColumn(
            modifier = modifier
                .background(Color.Black)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(bitmaps) { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "PDF Page",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
