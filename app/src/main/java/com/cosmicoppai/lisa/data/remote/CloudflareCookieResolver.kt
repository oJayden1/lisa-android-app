package com.cosmicoppai.lisa.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import com.cosmicoppai.lisa.data.local.dao.SiteStateDao
import com.cosmicoppai.lisa.data.local.entity.SiteStateEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/"
)

data class CookieChallengeRequest(
    val siteName: String,
    val siteUrl: String,
    val onCompleted: (Boolean) -> Unit
)

data class WebViewFetchResult(
    val statusCode: Int,
    val body: String,
    val finalUrl: String,
    val resolvedOrigin: String,
    val hasInteractiveCaptcha: Boolean = false
)

data class WebViewBinaryFetchResult(
    val statusCode: Int,
    val contentType: String,
    val bytes: ByteArray,
    val hasInteractiveCaptcha: Boolean = false
)

class InteractiveCaptchaException(
    message: String,
    val siteUrl: String = ""
) : IOException(message)

@Singleton
class DynamicCookieJar @Inject constructor() : CookieJar {
    private val hostCookies = ConcurrentHashMap<String, MutableList<Cookie>>()

    fun setCookiesForUrl(url: String, cookies: List<StoredCookie>) {
        val parsed = cookies.mapNotNull { sc ->
            runCatching {
                Cookie.Builder()
                    .name(sc.name)
                    .value(sc.value)
                    .domain(sc.domain.removePrefix("."))
                    .path(sc.path.ifBlank { "/" })
                    .build()
            }.getOrNull()
        }
        val host = extractHost(url)
        if (host.isNotBlank()) {
            val hostList = hostCookies.getOrPut(host) { mutableListOf() }
            synchronized(hostList) {
                parsed.forEach { cookie ->
                    hostList.removeAll { it.name == cookie.name }
                    hostList.add(cookie)
                }
            }
        }
        parsed.forEach { cookie ->
            val domainHost = cookie.domain.removePrefix(".")
            if (domainHost.isNotBlank() && domainHost != host) {
                val list = hostCookies.getOrPut(domainHost) { mutableListOf() }
                synchronized(list) {
                    list.removeAll { it.name == cookie.name }
                    list.add(cookie)
                }
            }
        }
    }

    fun clearAll() {
        hostCookies.clear()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = hostCookies.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { incoming ->
                list.removeAll { it.name == incoming.name && it.domain == incoming.domain }
                list.add(incoming)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = mutableListOf<Cookie>()
        hostCookies.forEach { (domain, cookies) ->
            if (url.host == domain || url.host.endsWith(".$domain")) {
                synchronized(cookies) {
                    cookies.removeAll { it.expiresAt < now }
                    matched.addAll(cookies)
                }
            }
        }
        return matched.distinctBy { it.name }
    }

    private fun extractHost(url: String): String {
        return url.removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
    }
}

@Singleton
class CloudflareCookieResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val siteStateDao: SiteStateDao,
    private val cookieJar: DynamicCookieJar,
    private val json: Json
) {
    private val mutex = Mutex()
    private val ttlMs = 24L * 60L * 60L * 1000L

    private val _interactiveChallenge = MutableStateFlow<CookieChallengeRequest?>(null)
    val interactiveChallenge: StateFlow<CookieChallengeRequest?> = _interactiveChallenge.asStateFlow()

    @Volatile
    var cachedUserAgent: String = DEFAULT_USER_AGENT
        private set

    @Volatile
    var resolvedOriginUrl: String? = null
        private set

    private var hiddenWebView: WebView? = null
    @Volatile
    private var needsHiddenWebViewReset: Boolean = false
    private val pendingFetches = ConcurrentHashMap<String, CompletableDeferred<WebViewFetchResult>>()

    private val imageMutex = Mutex()
    private var imageWebView: WebView? = null
    private val pendingBinaryFetches = ConcurrentHashMap<String, CompletableDeferred<WebViewBinaryFetchResult>>()
    private val redirectedImageOrigins = ConcurrentHashMap<String, String>()
    @Volatile
    private var activeImageOrigin: String = ""
    private val inFlightImageFetches = AtomicInteger(0)

    fun requestInteractiveVerification(
        siteName: String = ANIMEPAHE_SITE,
        siteUrl: String,
        onCompleted: (Boolean) -> Unit
    ) {
        val targetUrl = siteUrl.ifBlank { resolvedOriginUrl ?: "https://animepahe.pw" }
        val completed = AtomicBoolean(false)
        val previous = _interactiveChallenge.value
        var requestRef: CookieChallengeRequest? = null
        val request = CookieChallengeRequest(
            siteName = siteName,
            siteUrl = targetUrl,
            onCompleted = { success ->
                if (completed.compareAndSet(false, true)) {
                    if (_interactiveChallenge.value === requestRef) {
                        _interactiveChallenge.value = null
                    }
                    onCompleted(success)
                }
            }
        )
        requestRef = request
        _interactiveChallenge.value = request
        previous?.onCompleted?.invoke(false)
    }

    fun dismissInteractiveChallenge() {
        val current = _interactiveChallenge.value
        _interactiveChallenge.value = null
        current?.onCompleted?.invoke(false)
    }

    @Keep
    private inner class FetchBridge {
        @JavascriptInterface
        fun onFetchComplete(requestId: String, statusCode: Int, finalUrl: String, body: String) {
            val origin = extractOrigin(finalUrl)
            pendingFetches.remove(requestId)?.complete(
                WebViewFetchResult(
                    statusCode = statusCode,
                    body = body,
                    finalUrl = finalUrl,
                    resolvedOrigin = origin,
                    hasInteractiveCaptcha = hasInteractiveCaptchaMarker(body)
                )
            )
        }

        @JavascriptInterface
        fun onFetchError(requestId: String, error: String) {
            pendingFetches.remove(requestId)?.completeExceptionally(IOException(error))
        }
    }

    @Keep
    private inner class ImageFetchBridge {
        @JavascriptInterface
        fun onBinaryComplete(requestId: String, statusCode: Int, contentType: String, base64Data: String) {
            val bytes = if (base64Data.isNotBlank()) {
                runCatching { Base64.decode(base64Data, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            } else {
                ByteArray(0)
            }
            pendingBinaryFetches.remove(requestId)?.complete(
                WebViewBinaryFetchResult(
                    statusCode = statusCode,
                    contentType = contentType.ifBlank { "image/jpeg" },
                    bytes = bytes,
                    hasInteractiveCaptcha = false
                )
            )
        }

        @JavascriptInterface
        fun onBinaryTextFallback(requestId: String, statusCode: Int, contentType: String, body: String) {
            pendingBinaryFetches.remove(requestId)?.complete(
                WebViewBinaryFetchResult(
                    statusCode = statusCode,
                    contentType = contentType.ifBlank { "text/html" },
                    bytes = ByteArray(0),
                    hasInteractiveCaptcha = hasInteractiveCaptchaMarker(body)
                )
            )
        }

        @JavascriptInterface
        fun onBinaryError(requestId: String, error: String) {
            pendingBinaryFetches.remove(requestId)?.completeExceptionally(IOException(error))
        }
    }

    suspend fun ensureSession(
        siteName: String = ANIMEPAHE_SITE,
        siteUrl: String,
        forceRefresh: Boolean = false
    ): String = mutex.withLock {
        if (!forceRefresh) {
            val saved = siteStateDao.getState(siteName)
            if (saved != null && System.currentTimeMillis() - saved.createdOnEpochMs < ttlMs) {
                val cookies = runCatching {
                    json.decodeFromString<List<StoredCookie>>(saved.cookiesJson)
                }.getOrDefault(emptyList())
                if (cookies.isNotEmpty()) {
                    cachedUserAgent = saved.userAgent.ifBlank { DEFAULT_USER_AGENT }
                    cookieJar.setCookiesForUrl(siteUrl, cookies)
                    resolvedOriginUrl?.let { cookieJar.setCookiesForUrl(it, cookies) }
                    return@withLock cachedUserAgent
                }
            }
        }

        val silentResult = runSilentWebViewPassLocked(siteUrl)
        if (silentResult.hasInteractiveCaptcha) {
            throw InteractiveCaptchaException(
                message = "AnimePahe requires human verification on this network.",
                siteUrl = silentResult.finalUrl.ifBlank { siteUrl }
            )
        }
        if (silentResult.cookies.isNotEmpty()) {
            persistCookies(
                siteName = siteName,
                siteUrl = silentResult.finalUrl.ifBlank { siteUrl },
                cookies = silentResult.cookies,
                userAgent = silentResult.userAgent
            )
        }
        // Never pop up WebViewChallengeDialog unexpectedly during anime loading/playback.
        return@withLock silentResult.userAgent
    }

    /**
     * Executes a request via the persistent offscreen Chromium WebView without showing any UI.
     * Supports same-origin fetch() calls (automatically following host redirects such as .com -> .pw)
     * as well as direct HTML page navigation for cross-origin embed pages (e.g. Kwik).
     */
    suspend fun fetchViaHiddenWebView(
        url: String,
        originUrl: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        directPageNavigation: Boolean = false
    ): WebViewFetchResult = mutex.withLock {
        val wv = getOrCreateWebViewOnMain()

        if (directPageNavigation) {
            val pageResult = loadUrlInWebViewLocked(wv, url, referer)
            val cookies = readCookiesForUrls(listOf(url, pageResult.finalUrl, originUrl))
            if (cookies.isNotEmpty()) {
                persistCookies(ANIMEPAHE_SITE, pageResult.finalUrl.ifBlank { url }, cookies, cachedUserAgent)
            }
            return@withLock pageResult
        }

        // Ensure hidden WebView is settled on the target site origin first (handling redirects like .com -> .pw)
        val currentOrigin = withContext(Dispatchers.Main) {
            extractOrigin(wv.url.orEmpty())
        }
        val targetOrigin = resolvedOriginUrl?.takeIf { isAnimepaheOrigin(it) && isAnimepaheOrigin(originUrl) }
            ?: extractOrigin(originUrl)

        var activeOrigin = currentOrigin
        if (needsHiddenWebViewReset || activeOrigin.isBlank() || activeOrigin != targetOrigin) {
            val loadTarget = if (targetOrigin.isNotBlank() && isAnimepaheOrigin(targetOrigin)) targetOrigin else originUrl
            val originLoad = loadUrlInWebViewLocked(wv, loadTarget, null)
            if (originLoad.hasInteractiveCaptcha) {
                return@withLock originLoad
            }
            needsHiddenWebViewReset = false
            activeOrigin = originLoad.resolvedOrigin.ifBlank { targetOrigin }
            if (isAnimepaheOrigin(activeOrigin)) {
                resolvedOriginUrl = activeOrigin
            }
        }

        val rewrittenUrl = rewriteOriginIfMirror(url, activeOrigin)
        val firstAttempt = executeJsFetchLocked(wv, rewrittenUrl, extraHeaders)
        if (firstAttempt.hasInteractiveCaptcha) {
            return@withLock firstAttempt
        }

        // If fetch hit 403/503 or JS interstitial, reload origin once passively and retry fetch
        val finalAttempt = if (firstAttempt.statusCode == 403 ||
            firstAttempt.statusCode == 503 ||
            isChallengeInterstitial(firstAttempt.body)
        ) {
            val reload = loadUrlInWebViewLocked(wv, activeOrigin, null)
            if (reload.hasInteractiveCaptcha) {
                return@withLock reload
            }
            activeOrigin = reload.resolvedOrigin.ifBlank { activeOrigin }
            if (isAnimepaheOrigin(activeOrigin)) {
                resolvedOriginUrl = activeOrigin
            }
            val retriedUrl = rewriteOriginIfMirror(url, activeOrigin)
            executeJsFetchLocked(wv, retriedUrl, extraHeaders)
        } else {
            firstAttempt
        }

        val cookies = readCookiesForUrls(listOf(originUrl, activeOrigin, finalAttempt.finalUrl))
        if (cookies.isNotEmpty()) {
            persistCookies(ANIMEPAHE_SITE, activeOrigin, cookies, cachedUserAgent)
        }
        return@withLock finalAttempt
    }

    @Volatile
    private var lastImageOriginRefreshMs: Long = 0L

    /**
     * Fetches binary image bytes via a dedicated offscreen Chromium WebView (`imageWebView`)
     * so blocked image hosts like `i.animepahe.pw` or MangaKatana image shards do not interfere
     * with the main API `hiddenWebView`. Same-origin fetches run concurrently, while switching
     * to a different image host waits for in-flight fetches to settle so `loadUrl` never aborts
     * another shard's active JS `fetch()`.
     */
    suspend fun fetchBinaryViaHiddenWebView(
        url: String,
        referer: String? = null
    ): WebViewBinaryFetchResult? {
        val initialOrigin = extractOrigin(url)
        if (initialOrigin.isBlank()) return null

        val prepared = prepareImageWebViewForUrl(url, initialOrigin, referer, forceReload = false)
        if (prepared.captchaResult != null) {
            return prepared.captchaResult
        }

        val firstAttempt = try {
            runCatching { executeBinaryJsFetch(prepared.webView, prepared.effectiveUrl) }.getOrNull()
        } finally {
            inFlightImageFetches.decrementAndGet()
        }

        if (firstAttempt != null &&
            firstAttempt.statusCode in 200..299 &&
            firstAttempt.bytes.isNotEmpty() &&
            !firstAttempt.contentType.lowercase().startsWith("text/html")
        ) {
            return firstAttempt
        }
        if (firstAttempt?.hasInteractiveCaptcha == true) {
            return firstAttempt
        }

        // Refresh path/origin clearance inside mutex if needed, then retry fetch once
        val retryPrepared = prepareImageWebViewForUrl(url, initialOrigin, referer, forceReload = true)
        if (retryPrepared.captchaResult != null) {
            return retryPrepared.captchaResult
        }

        return try {
            runCatching { executeBinaryJsFetch(retryPrepared.webView, retryPrepared.effectiveUrl) }.getOrNull()
        } finally {
            inFlightImageFetches.decrementAndGet()
        }
    }

    private data class PreparedImageFetch(
        val webView: WebView,
        val effectiveUrl: String,
        val captchaResult: WebViewBinaryFetchResult? = null
    )

    private suspend fun prepareImageWebViewForUrl(
        url: String,
        initialOrigin: String,
        referer: String?,
        forceReload: Boolean
    ): PreparedImageFetch = imageMutex.withLock {
        val webView = getOrCreateImageWebViewOnMain()
        val currentOrigin = withContext(Dispatchers.Main) {
            extractOrigin(webView.url.orEmpty())
        }
        val mappedOrigin = redirectedImageOrigins[initialOrigin] ?: initialOrigin
        val now = System.currentTimeMillis()
        val shouldNavigate = if (forceReload) {
            (now - lastImageOriginRefreshMs > 3_000L) || currentOrigin != mappedOrigin || activeImageOrigin != mappedOrigin
        } else {
            currentOrigin != mappedOrigin || activeImageOrigin != mappedOrigin
        }

        var effectiveUrl = if (mappedOrigin != initialOrigin) {
            rewriteOrigin(url, mappedOrigin)
        } else {
            url
        }

        if (shouldNavigate) {
            waitForInFlightImageFetchesLocked()
            val originLoad = loadUrlInWebViewLocked(
                wv = webView,
                url = url,
                referer = referer,
                allowShortNonChallenge = true
            )
            val resolvedFinalUrl = originLoad.finalUrl.ifBlank { url }
            val resolvedOrigin = originLoad.resolvedOrigin
                .ifBlank { extractOrigin(resolvedFinalUrl) }
                .ifBlank { initialOrigin }
            syncImageHostCookies(resolvedOrigin, resolvedFinalUrl)
            activeImageOrigin = resolvedOrigin
            lastImageOriginRefreshMs = System.currentTimeMillis()

            if (resolvedOrigin != initialOrigin && hasSamePath(url, resolvedFinalUrl)) {
                redirectedImageOrigins[initialOrigin] = resolvedOrigin
            }
            if (originLoad.hasInteractiveCaptcha) {
                return@withLock PreparedImageFetch(
                    webView = webView,
                    effectiveUrl = resolvedFinalUrl,
                    captchaResult = WebViewBinaryFetchResult(
                        statusCode = 403,
                        contentType = "text/html",
                        bytes = ByteArray(0),
                        hasInteractiveCaptcha = true
                    )
                )
            }
            effectiveUrl = resolvedFinalUrl
        }

        inFlightImageFetches.incrementAndGet()
        PreparedImageFetch(webView = webView, effectiveUrl = effectiveUrl)
    }

    private suspend fun waitForInFlightImageFetchesLocked() {
        val deadline = System.currentTimeMillis() + 4_000L
        while (inFlightImageFetches.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(50L)
        }
    }

    private fun hasSamePath(urlA: String, urlB: String): Boolean {
        val a = urlA.toHttpUrlOrNull() ?: return false
        val b = urlB.toHttpUrlOrNull() ?: return false
        return a.encodedPath == b.encodedPath && a.encodedQuery == b.encodedQuery
    }

    private fun rewriteOrigin(url: String, targetOrigin: String): String {
        val parsedTarget = targetOrigin.toHttpUrlOrNull() ?: return url
        val parsedUrl = url.toHttpUrlOrNull() ?: return url
        return parsedUrl.newBuilder()
            .scheme(parsedTarget.scheme)
            .host(parsedTarget.host)
            .port(parsedTarget.port)
            .build()
            .toString()
    }

    private suspend fun syncImageHostCookies(origin: String, url: String) {
        val cookies = readCookiesForUrls(listOf(origin, url))
        if (cookies.isNotEmpty()) {
            cookieJar.setCookiesForUrl(origin, cookies)
            cookieJar.setCookiesForUrl(url, cookies)
        }
    }

    suspend fun clearCookies() = withContext(Dispatchers.IO) {
        siteStateDao.clearAll()
        cookieJar.clearAll()
        resolvedOriginUrl = null
        needsHiddenWebViewReset = true
        lastImageOriginRefreshMs = 0L
        activeImageOrigin = ""
        redirectedImageOrigins.clear()
        withContext(Dispatchers.Main) {
            runCatching {
                hiddenWebView?.loadUrl("about:blank")
                imageWebView?.loadUrl("about:blank")
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    suspend fun persistInteractiveResult(
        siteName: String,
        siteUrl: String,
        userAgentOverride: String? = null
    ) {
        withContext(Dispatchers.Main) {
            runCatching { CookieManager.getInstance().flush() }
        }
        val origin = extractOrigin(siteUrl)
        if (origin.isNotBlank() && isAnimepaheOrigin(origin)) {
            resolvedOriginUrl = origin
        }
        val cookies = readCookiesForUrls(listOf(siteUrl, origin, resolvedOriginUrl.orEmpty()))
        val ua = userAgentOverride?.takeIf { it.isNotBlank() } ?: resolveWebViewUserAgent()
        if (cookies.isNotEmpty()) {
            persistCookies(siteName, resolvedOriginUrl ?: origin.ifBlank { siteUrl }, cookies, ua)
        } else {
            cachedUserAgent = ua
        }
        needsHiddenWebViewReset = true
        lastImageOriginRefreshMs = 0L
        activeImageOrigin = ""
        redirectedImageOrigins.clear()
    }

    private suspend fun persistCookies(
        siteName: String,
        siteUrl: String,
        cookies: List<StoredCookie>,
        userAgent: String
    ) {
        cachedUserAgent = userAgent
        cookieJar.setCookiesForUrl(siteUrl, cookies)
        siteStateDao.upsert(
            SiteStateEntity(
                siteName = siteName,
                cookiesJson = json.encodeToString(cookies),
                userAgent = userAgent,
                createdOnEpochMs = System.currentTimeMillis()
            )
        )
    }

    private data class WebViewPassResult(
        val cookies: List<StoredCookie>,
        val userAgent: String,
        val finalUrl: String,
        val hasInteractiveCaptcha: Boolean = false
    )

    private suspend fun runSilentWebViewPassLocked(siteUrl: String): WebViewPassResult {
        val wv = getOrCreateWebViewOnMain()
        val result = loadUrlInWebViewLocked(wv, siteUrl, null)
        if (result.resolvedOrigin.isNotBlank() && isAnimepaheOrigin(result.resolvedOrigin)) {
            resolvedOriginUrl = result.resolvedOrigin
        }
        val cookies = readCookiesForUrls(listOf(siteUrl, result.finalUrl, result.resolvedOrigin))
        return WebViewPassResult(
            cookies = cookies,
            userAgent = cachedUserAgent,
            finalUrl = result.finalUrl.ifBlank { siteUrl },
            hasInteractiveCaptcha = result.hasInteractiveCaptcha
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun getOrCreateWebViewOnMain(): WebView = withContext(Dispatchers.Main) {
        hiddenWebView?.let { return@withContext it }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val wv = WebView(context)
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WebSettings.getDefaultUserAgent(context)
        }
        cachedUserAgent = wv.settings.userAgentString ?: DEFAULT_USER_AGENT
        wv.addJavascriptInterface(FetchBridge(), "LisaBridge")
        hiddenWebView = wv
        wv
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun getOrCreateImageWebViewOnMain(): WebView = withContext(Dispatchers.Main) {
        imageWebView?.let { return@withContext it }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val wv = WebView(context)
        cm.setAcceptThirdPartyCookies(wv, true)
        val defaultUa = WebSettings.getDefaultUserAgent(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = defaultUa
        }
        cachedUserAgent = wv.settings.userAgentString ?: defaultUa.ifBlank { DEFAULT_USER_AGENT }
        wv.addJavascriptInterface(ImageFetchBridge(), "LisaImageBridge")
        imageWebView = wv
        wv
    }

    private suspend fun loadUrlInWebViewLocked(
        wv: WebView,
        url: String,
        referer: String?,
        allowShortNonChallenge: Boolean = false
    ): WebViewFetchResult {
        val pageFinished = CompletableDeferred<String>()
        var latestUrl = url

        withContext(Dispatchers.Main) {
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!finishedUrl.isNullOrBlank()) {
                        latestUrl = finishedUrl
                    }
                    if (!pageFinished.isCompleted) {
                        pageFinished.complete(latestUrl)
                    }
                }
            }
            if (!referer.isNullOrBlank()) {
                wv.loadUrl(url, mapOf("Referer" to referer))
            } else {
                wv.loadUrl(url)
            }
        }

        withTimeoutOrNull(8_000L) { pageFinished.await() }

        // Passively wait briefly only if the page shows a JS interstitial redirect (never interact with Turnstile)
        var html = ""
        var finalUrl = url
        repeat(8) { idx ->
            val snapshot = readWebViewSnapshot(wv)
            html = snapshot.first
            finalUrl = snapshot.second.ifBlank { latestUrl }
            if (hasInteractiveCaptchaMarker(html)) {
                return WebViewFetchResult(
                    statusCode = 403,
                    body = html,
                    finalUrl = finalUrl,
                    resolvedOrigin = extractOrigin(finalUrl),
                    hasInteractiveCaptcha = true
                )
            }
            val settledShortOrigin = allowShortNonChallenge &&
                !isChallengeInterstitial(html) &&
                ((idx >= 1 && html.isNotBlank()) || html.contains("<img", ignoreCase = true))
            if ((!isChallengeInterstitial(html) && html.length > 200) || settledShortOrigin) {
                return WebViewFetchResult(
                    statusCode = 200,
                    body = html,
                    finalUrl = finalUrl,
                    resolvedOrigin = extractOrigin(finalUrl),
                    hasInteractiveCaptcha = false
                )
            }
            if (idx < 7) delay(600L)
        }

        return WebViewFetchResult(
            statusCode = if (isChallengeInterstitial(html)) 403 else 200,
            body = html,
            finalUrl = finalUrl,
            resolvedOrigin = extractOrigin(finalUrl),
            hasInteractiveCaptcha = hasInteractiveCaptchaMarker(html)
        )
    }

    private suspend fun executeJsFetchLocked(
        wv: WebView,
        url: String,
        extraHeaders: Map<String, String>
    ): WebViewFetchResult {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<WebViewFetchResult>()
        pendingFetches[reqId] = deferred

        val safeHeaders = JSONObject().apply {
            put("Accept", "application/json, text/javascript, text/html, */*; q=0.01")
            if (url.contains("/api?")) {
                put("X-Requested-With", "XMLHttpRequest")
            }
            extraHeaders.forEach { (k, v) ->
                if (!k.equals("User-Agent", true) && !k.equals("Referer", true) && !k.equals("Origin", true)) {
                    put(k, v)
                }
            }
        }

        val quotedUrl = JSONObject.quote(url)
        val quotedId = JSONObject.quote(reqId)
        val headersJson = safeHeaders.toString()

        val js = """
            (function() {
                fetch($quotedUrl, {
                    method: 'GET',
                    credentials: 'include',
                    redirect: 'follow',
                    headers: $headersJson
                }).then(function(resp) {
                    return resp.text().then(function(body) {
                        window.LisaBridge.onFetchComplete($quotedId, resp.status, resp.url || $quotedUrl, body || '');
                    });
                }).catch(function(err) {
                    window.LisaBridge.onFetchError($quotedId, String(err));
                });
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(js, null)
        }

        return try {
            withTimeoutOrNull(15_000L) { deferred.await() }
                ?: throw IOException("Timed out waiting for background WebView request to $url")
        } finally {
            pendingFetches.remove(reqId)
        }
    }

    private suspend fun executeBinaryJsFetch(
        wv: WebView,
        url: String
    ): WebViewBinaryFetchResult {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<WebViewBinaryFetchResult>()
        pendingBinaryFetches[reqId] = deferred

        val quotedUrl = JSONObject.quote(url)
        val quotedId = JSONObject.quote(reqId)

        val js = """
            (function() {
                fetch($quotedUrl, {
                    method: 'GET',
                    credentials: 'include',
                    redirect: 'follow'
                }).then(function(resp) {
                    var ct = resp.headers.get('content-type') || '';
                    if (!resp.ok || ct.toLowerCase().indexOf('text/html') !== -1) {
                        return resp.text().then(function(txt) {
                            window.LisaImageBridge.onBinaryTextFallback($quotedId, resp.status, ct, txt || '');
                        });
                    }
                    return resp.blob().then(function(blob) {
                        return new Promise(function(resolve, reject) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var dataUrl = reader.result || '';
                                var comma = dataUrl.indexOf(',');
                                var b64 = comma >= 0 ? dataUrl.substring(comma + 1) : '';
                                window.LisaImageBridge.onBinaryComplete($quotedId, resp.status, ct || blob.type || 'image/jpeg', b64);
                                resolve();
                            };
                            reader.onerror = function(e) { reject(e); };
                            reader.readAsDataURL(blob);
                        });
                    });
                }).catch(function(err) {
                    window.LisaImageBridge.onBinaryError($quotedId, String(err));
                });
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(js, null)
        }

        return try {
            withTimeoutOrNull(12_000L) { deferred.await() }
                ?: throw IOException("Timed out waiting for background WebView image fetch to $url")
        } finally {
            pendingBinaryFetches.remove(reqId)
        }
    }

    private suspend fun readWebViewSnapshot(wv: WebView): Pair<String, String> {
        val resultDeferred = CompletableDeferred<Pair<String, String>>()
        withContext(Dispatchers.Main) {
            val currentUrl = wv.url.orEmpty()
            wv.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { raw ->
                val decoded = if (raw != null && raw != "null") {
                    runCatching { org.json.JSONTokener(raw).nextValue()?.toString().orEmpty() }.getOrDefault("")
                } else {
                    ""
                }
                resultDeferred.complete(decoded to currentUrl)
            }
        }
        return withTimeoutOrNull(2_500L) { resultDeferred.await() } ?: ("" to "")
    }

    private suspend fun readCookiesForUrls(urls: List<String>): List<StoredCookie> =
        withContext(Dispatchers.Main) {
            val cm = CookieManager.getInstance()
            val all = linkedMapOf<String, StoredCookie>()
            val candidateUrls = (urls + listOf(
                "https://animepahe.pw",
                "https://animepahe.org",
                "https://animepahe.com"
            )).filter { it.isNotBlank() }.distinct()

            candidateUrls.forEach { target ->
                val raw = runCatching { cm.getCookie(target) }.getOrNull() ?: return@forEach
                val domain = target.removePrefix("https://").removePrefix("http://").substringBefore("/")
                if (domain.isBlank()) return@forEach
                raw.split(";").forEach { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val name = pair.substring(0, idx).trim()
                        val value = pair.substring(idx + 1).trim()
                        if (name.isNotEmpty()) {
                            all["$domain:$name"] = StoredCookie(name, value, domain)
                        }
                    }
                }
            }
            all.values.toList()
        }

    private suspend fun resolveWebViewUserAgent(): String = withContext(Dispatchers.Main) {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(DEFAULT_USER_AGENT)
    }

    private fun extractOrigin(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return ""
        return "${parsed.scheme}://${parsed.host}"
    }

    private fun isAnimepaheOrigin(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()
        return host.contains("animepahe")
    }

    private fun rewriteOriginIfMirror(url: String, targetOrigin: String): String {
        val parsedTarget = targetOrigin.toHttpUrlOrNull() ?: return url
        val parsedUrl = url.toHttpUrlOrNull() ?: return url
        return if (parsedUrl.host.contains("animepahe", ignoreCase = true) &&
            parsedTarget.host.contains("animepahe", ignoreCase = true)
        ) {
            parsedUrl.newBuilder()
                .scheme(parsedTarget.scheme)
                .host(parsedTarget.host)
                .port(parsedTarget.port)
                .build()
                .toString()
        } else {
            url
        }
    }

    companion object {
        const val ANIMEPAHE_SITE = "animepahe"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

        fun isChallengeInterstitial(html: String): Boolean {
            if (html.isBlank()) return false
            val lower = html.lowercase()
            return lower.contains("<title>just a moment") ||
                lower.contains("ddos-guard") ||
                lower.contains("/cdn-cgi/challenge-platform") ||
                lower.contains("checking your browser before accessing")
        }

        fun hasInteractiveCaptchaMarker(html: String): Boolean {
            if (html.isBlank()) return false
            val lower = html.lowercase()
            return lower.contains("cf-turnstile") ||
                lower.contains("challenges.cloudflare.com/turnstile") ||
                lower.contains("g-recaptcha") ||
                lower.contains("h-captcha") ||
                lower.contains("verify you are human")
        }
    }
}
