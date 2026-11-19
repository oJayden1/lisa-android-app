package com.cosmicoppai.lisa.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cosmicoppai.lisa.R
import com.cosmicoppai.lisa.data.remote.CloudflareCookieResolver
import com.cosmicoppai.lisa.data.remote.CookieChallengeRequest
import com.cosmicoppai.lisa.model.AnimeSearchItem
import com.cosmicoppai.lisa.model.AppMode
import com.cosmicoppai.lisa.model.MangaSearchItem
import com.cosmicoppai.lisa.ui.theme.LisaBorder
import com.cosmicoppai.lisa.ui.theme.LisaPrimary
import com.cosmicoppai.lisa.ui.theme.LisaStarGold
import com.cosmicoppai.lisa.ui.theme.LisaSurface
import com.cosmicoppai.lisa.ui.theme.LisaSurfaceVariant
import com.cosmicoppai.lisa.ui.theme.LisaTextPrimary
import com.cosmicoppai.lisa.ui.theme.LisaTextSecondary
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun AppModeSwitchBar(
    mode: AppMode,
    onModeChange: (AppMode) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        AppMode.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onModeChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AppMode.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = LisaPrimary.copy(alpha = 0.22f),
                    activeContentColor = LisaPrimary,
                    inactiveContainerColor = LisaSurface,
                    inactiveContentColor = LisaTextSecondary
                )
            ) {
                Text(entry.label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun AnimePosterCard(
    item: AnimeSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.type.isNotBlank()) {
                            Surface(
                                color = LisaPrimary.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = item.type,
                                    color = Color.Black,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (item.score > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = LisaStarGold,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = String.format(Locale.US, "%.1f", item.score),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                val meta = buildList {
                    if (item.noOfEpisodes.isNotBlank() && item.noOfEpisodes != "0") {
                        add("${item.noOfEpisodes} eps")
                    }
                    if (item.year.isNotBlank() && item.year != "None") add(item.year)
                }.joinToString(" • ")
                Text(
                    text = meta.ifBlank { item.status },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = LisaTextSecondary
                )
            }
        }
    }
}

@Composable
fun MangaPosterCard(
    item: MangaSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
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
                val context = LocalContext.current
                var loadFailed by remember(item.poster) { mutableStateOf(false) }
                if (item.poster.isNotBlank() && !loadFailed) {
                    val posterRequest = remember(item.poster) {
                        ImageRequest.Builder(context)
                            .data(item.poster)
                            .addHeader("Referer", "https://mangakatana.com/")
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        onError = { loadFailed = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = LisaTextSecondary.copy(alpha = 0.55f),
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center)
                    )
                }
                if (item.status.isNotBlank()) {
                    Surface(
                        color = LisaSurface.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = item.status,
                            color = LisaPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
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
                    text = if (item.totalChps.isNotBlank() && item.totalChps != "0") {
                        "${item.totalChps} chapters"
                    } else {
                        item.genres.take(2).joinToString(", ")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = LisaTextSecondary
                )
            }
        }
    }
}

@Composable
fun LisaTopBackBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = LisaTextPrimary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

@Composable
fun LisaLoadingView(message: String = "Loading...") {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = LisaPrimary)
        Spacer(Modifier.height(14.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = LisaTextSecondary)
    }
}

@Composable
fun LisaNotFoundView(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.not_found),
            contentDescription = title,
            modifier = Modifier
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun LisaErrorView(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        val hasSecondary = onSecondaryAction != null && !secondaryActionLabel.isNullOrBlank()
        if (hasSecondary || onRetry != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasSecondary) {
                    Button(
                        onClick = onSecondaryAction!!,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(secondaryActionLabel!!)
                    }
                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                } else if (onRetry != null) {
                    Button(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewChallengeDialog(
    challenge: CookieChallengeRequest,
    onDone: (finalUrl: String, userAgent: String) -> Unit,
    onDismiss: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var latestUrl by remember(challenge) { mutableStateOf(challenge.siteUrl) }
    var activeUserAgent by remember(challenge) { mutableStateOf(CloudflareCookieResolver.DEFAULT_USER_AGENT) }
    val completedOnce = remember(challenge) { AtomicBoolean(false) }

    fun finishOnce(url: String, ua: String) {
        if (completedOnce.compareAndSet(false, true)) {
            runCatching { CookieManager.getInstance().flush() }
            onDone(url.ifBlank { challenge.siteUrl }, ua)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = LisaSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Verify ${challenge.siteName} access",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Closes automatically once verification passes",
                            style = MaterialTheme.typography.labelSmall,
                            color = LisaTextSecondary
                        )
                    }
                    Row {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = {
                                finishOnce(
                                    webViewRef?.url.orEmpty().ifBlank { latestUrl },
                                    activeUserAgent
                                )
                            }
                        ) { Text("Done") }
                    }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            val defaultUa = WebSettings.getDefaultUserAgent(ctx)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = defaultUa
                            activeUserAgent = settings.userAgentString ?: defaultUa
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank()) {
                                        latestUrl = url
                                    }
                                    if (completedOnce.get() || view == null || url.isNullOrBlank()) return
                                    if (url == "about:blank") return
                                    view.evaluateJavascript(
                                        "document.documentElement ? document.documentElement.outerHTML : ''"
                                    ) { raw ->
                                        if (completedOnce.get()) return@evaluateJavascript
                                        val html = if (raw != null && raw != "null") {
                                            runCatching {
                                                JSONTokener(raw).nextValue()?.toString().orEmpty()
                                            }.getOrDefault("")
                                        } else {
                                            ""
                                        }
                                        val cookies = runCatching { cm.getCookie(url).orEmpty() }.getOrDefault("")
                                        val passed = html.length > 200 &&
                                            cookies.isNotBlank() &&
                                            !CloudflareCookieResolver.isChallengeInterstitial(html) &&
                                            !CloudflareCookieResolver.hasInteractiveCaptchaMarker(html)
                                        if (passed) {
                                            finishOnce(url, activeUserAgent)
                                        }
                                    }
                                }
                            }
                            webChromeClient = WebChromeClient()
                            webViewRef = this
                            loadUrl(challenge.siteUrl)
                        }
                    }
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
        .coerceIn(0, units.lastIndex)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
