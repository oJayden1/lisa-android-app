package com.cosmicoppai.lisa.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cosmicoppai.lisa.data.local.entity.DownloadTaskEntity
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.DownloadFormat
import com.cosmicoppai.lisa.model.DownloadStatus
import com.cosmicoppai.lisa.model.MediaType
import com.cosmicoppai.lisa.ui.components.formatBytes
import com.cosmicoppai.lisa.ui.theme.LisaBorder
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaTextPrimary
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    val activeDownloads: StateFlow<List<DownloadTaskEntity>> = repository.observeActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completedDownloads: StateFlow<List<DownloadTaskEntity>> = repository.observeCompletedDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(taskId: Long) {
        viewModelScope.launch { repository.pauseDownload(taskId) }
    }

    fun resume(taskId: Long) {
        viewModelScope.launch { repository.resumeDownload(taskId) }
    }

    fun delete(taskId: Long) {
        viewModelScope.launch { repository.cancelOrDeleteDownload(taskId) }
    }
}

@Composable
fun DownloadsScreen(
    onPlayLocalVideo: (Long) -> Unit,
    onReadLocalManga: (Long) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val active by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val completed by viewModel.completedDownloads.collectAsStateWithLifecycle()
    val groupedCompleted = remember(completed) {
        completed.groupBy { it.mediaType to it.seriesName }
    }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var deleteCandidate by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    val context = LocalContext.current

    deleteCandidate?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete Download") },
            text = { Text("Delete ${task.fileName} from device storage?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(task.id)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Active Downloads",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (active.isEmpty()) {
            item {
                Text(
                    text = "No Active Downloads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LisaTextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(active, key = { it.id }) { task ->
                ActiveDownloadCard(
                    task = task,
                    onPause = { viewModel.pause(task.id) },
                    onResume = { viewModel.resume(task.id) },
                    onCancel = { viewModel.delete(task.id) }
                )
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Library",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (groupedCompleted.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.DownloadDone,
                        contentDescription = null,
                        tint = LisaTextSecondary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("No Previous Downloads", color = LisaTextSecondary)
                }
            }
        } else {
            groupedCompleted.forEach { (keyPair, tasks) ->
                val (mediaType, seriesName) = keyPair
                val groupKey = "${mediaType.name}_$seriesName"
                val isExpanded = expandedGroups[groupKey] ?: true
                item(key = groupKey) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = LisaSurface),
                        border = BorderStroke(1.dp, LisaBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedGroups[groupKey] = !isExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = seriesName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${mediaType.name.lowercase().replaceFirstChar { it.uppercase() }} • ${tasks.size} items",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = LisaTextSecondary
                                )
                            }

                            if (isExpanded) {
                                Spacer(Modifier.height(8.dp))
                                tasks.forEach { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.fileName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${item.outputFormat.name} • ${formatBytes(item.totalBytes)}",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        IconButton(onClick = {
                                            if (item.mediaType == MediaType.ANIME) {
                                                onPlayLocalVideo(item.id)
                                            } else {
                                                onReadLocalManga(item.id)
                                            }
                                        }) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                contentDescription = "Open",
                                                tint = LisaPrimary
                                            )
                                        }
                                        if (item.outputFormat != DownloadFormat.JPG && !item.fileLocation.isNullOrBlank()) {
                                            IconButton(onClick = {
                                                val file = File(item.fileLocation)
                                                if (file.exists()) {
                                                    val uri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        file
                                                    )
                                                    val mime = if (item.outputFormat == DownloadFormat.PDF) {
                                                        "application/pdf"
                                                    } else {
                                                        "video/mp4"
                                                    }
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, mime)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    runCatching {
                                                        context.startActivity(Intent.createChooser(intent, "Open with"))
                                                    }
                                                }
                                            }) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = "Open Externally",
                                                    tint = LisaTextPrimary
                                                )
                                            }
                                        }
                                        IconButton(onClick = { deleteCandidate = item }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error
                                            )
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
}

@Composable
private fun ActiveDownloadCard(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val progress = if (task.totalSegments > 0) {
        (task.downloadedSegments.toFloat() / task.totalSegments.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LisaSurface),
        border = BorderStroke(1.dp, LisaBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.seriesName,
                        style = MaterialTheme.typography.labelMedium,
                        color = LisaPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = LisaPrimary)
                    }
                } else {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = LisaTextPrimary)
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = LisaPrimary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val statusTxt = when (task.status) {
                    DownloadStatus.STARTED -> "${task.downloadedSegments}/${task.totalSegments} • ${formatBytes(task.speedBytesPerSec)}/s"
                    DownloadStatus.FAILED -> "Failed: ${task.errorMessage ?: "error"}"
                    else -> "${task.status.name.lowercase().replaceFirstChar { it.uppercase() }} (${task.downloadedSegments}/${task.totalSegments})"
                }
                Text(text = statusTxt, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = LisaTextPrimary
                )
            }
        }
    }
}
