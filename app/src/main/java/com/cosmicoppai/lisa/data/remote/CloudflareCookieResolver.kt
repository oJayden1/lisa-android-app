package com.cosmicoppai.lisa.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.concurrent.ConcurrentHashMap
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
        hostCookies[host] = parsed.toMutableList()
        parsed.forEach { cookie ->
            val domainHost = cookie.domain.removePrefix(".")
            if (domainHost.isNotBlank() && domainHost != host) {
                val list = hostCookies.getOrPut(domainHost) { mutableListOf() }
                list.removeAll { it.name == cookie.name }
                list.add(cookie)
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
                    return@withLock cachedUserAgent
                }
            }
        }

        val silentResult = runSilentWebViewPass(siteUrl)
        if (silentResult.cookies.isNotEmpty()) {
            persistCookies(siteName, siteUrl, silentResult.cookies, silentResult.userAgent)
            return@withLock silentResult.userAgent
        }

        // If silent clearance failed (e.g. interactive Cloudflare Turnstile), surface interactive sheet
        val solved = CompletableDeferred<Boolean>()
        _interactiveChallenge.value = CookieChallengeRequest(
            siteName = siteName,
            siteUrl = siteUrl,
            onCompleted = { ok -> solved.complete(ok) }
        )
        withTimeoutOrNull(120_000L) { solved.await() }
        _interactiveChallenge.value = null

        val afterInteractive = readCookiesFromManager(siteUrl)
        val ua = resolveWebViewUserAgent()
        if (afterInteractive.isNotEmpty()) {
            persistCookies(siteName, siteUrl, afterInteractive, ua)
        }
        return@withLock ua
    }

    suspend fun clearCookies() = withContext(Dispatchers.IO) {
        siteStateDao.clearAll()
        cookieJar.clearAll()
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    suspend fun persistInteractiveResult(siteName: String, siteUrl: String) {
        val cookies = readCookiesFromManager(siteUrl)
        val ua = resolveWebViewUserAgent()
        if (cookies.isNotEmpty()) {
            persistCookies(siteName, siteUrl, cookies, ua)
        }
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
        val userAgent: String
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentWebViewPass(siteUrl: String): WebViewPassResult {
        val loaded = CompletableDeferred<Unit>()
        var webView: WebView? = null
        var ua = DEFAULT_USER_AGENT

        withContext(Dispatchers.Main) {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val wv = WebView(context)
                webView = wv
                cm.setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = WebSettings.getDefaultUserAgent(context)
                }
                ua = wv.settings.userAgentString ?: DEFAULT_USER_AGENT
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!loaded.isCompleted) loaded.complete(Unit)
                    }
                }
                wv.loadUrl(siteUrl)
            }.onFailure {
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }

        withTimeoutOrNull(7_000L) { loaded.await() }
        // Match desktop LiSA's 5s settle delay for DDoS-Guard / JS clearance cookies
        delay(5_000L)

        val cookies = readCookiesFromManager(siteUrl)
        withContext(Dispatchers.Main) {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
        return WebViewPassResult(cookies, ua)
    }

    private suspend fun readCookiesFromManager(siteUrl: String): List<StoredCookie> =
        withContext(Dispatchers.Main) {
            val raw = runCatching { CookieManager.getInstance().getCookie(siteUrl) }.getOrNull()
                ?: return@withContext emptyList()
            val domain = siteUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")
            raw.split(";")
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isEmpty()) null else StoredCookie(name, value, domain)
                }
        }

    private suspend fun resolveWebViewUserAgent(): String = withContext(Dispatchers.Main) {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(DEFAULT_USER_AGENT)
    }

    companion object {
        const val ANIMEPAHE_SITE = "animepahe"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
    }
}
