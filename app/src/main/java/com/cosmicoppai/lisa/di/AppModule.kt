package com.cosmicoppai.lisa.di

import android.content.Context
import androidx.room.Room
import com.cosmicoppai.lisa.data.local.LiSADatabase
import com.cosmicoppai.lisa.data.local.dao.DownloadDao
import com.cosmicoppai.lisa.data.local.dao.ReadlistDao
import com.cosmicoppai.lisa.data.local.dao.SiteStateDao
import com.cosmicoppai.lisa.data.local.dao.WatchlistDao
import com.cosmicoppai.lisa.data.remote.CloudflareCookieResolver
import com.cosmicoppai.lisa.data.remote.DynamicCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class CoilClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LiSADatabase {
        return Room.databaseBuilder(
            context,
            LiSADatabase::class.java,
            "lisa_database.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides fun provideWatchlistDao(db: LiSADatabase): WatchlistDao = db.watchlistDao()
    @Provides fun provideReadlistDao(db: LiSADatabase): ReadlistDao = db.readlistDao()
    @Provides fun provideDownloadDao(db: LiSADatabase): DownloadDao = db.downloadDao()
    @Provides fun provideSiteStateDao(db: LiSADatabase): SiteStateDao = db.siteStateDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: DynamicCookieJar): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @CoilClient
    fun provideCoilOkHttpClient(
        @ApplicationContext context: Context,
        cookieJar: DynamicCookieJar,
        cookieResolver: CloudflareCookieResolver
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 10
        }
        val cacheDir = File(context.cacheDir, "http_image_cache")
        val headerInterceptor = Interceptor { chain ->
            val original = chain.request()
            val host = original.url.host.lowercase()
            val builder = original.newBuilder()

            if (original.header("User-Agent").isNullOrBlank()) {
                builder.header(
                    "User-Agent",
                    cookieResolver.cachedUserAgent.ifBlank { CloudflareCookieResolver.DEFAULT_USER_AGENT }
                )
            }
            if (original.header("Accept").isNullOrBlank()) {
                builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            }
            if (original.header("Referer").isNullOrBlank()) {
                val animepaheReferer = cookieResolver.resolvedOriginUrl
                    ?.trimEnd('/')
                    ?.plus("/")
                    ?: "https://animepahe.pw/"
                val inferredReferer = when {
                    host.contains("mangakatana") -> "https://mangakatana.com/"
                    host.contains("animepahe") || host.contains("pahe") -> animepaheReferer
                    host.contains("kwik") -> "https://kwik.cx/"
                    host.contains("myanimelist") -> "https://myanimelist.net/"
                    else -> null
                }
                if (inferredReferer != null) {
                    builder.header("Referer", inferredReferer)
                }
            }
            chain.proceed(builder.build())
        }

        val webViewImageFallbackInterceptor = Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host.lowercase()
            val response = chain.proceed(request)

            val isProtectedHost = host.contains("animepahe") ||
                host.contains("pahe") ||
                host.contains("kwik") ||
                host.contains("mangakatana")
            if (!isProtectedHost) {
                return@Interceptor response
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val blocked = response.code == 403 ||
                response.code == 503 ||
                contentType.startsWith("text/html")
            if (!blocked) {
                return@Interceptor response
            }

            val referer = request.header("Referer")
            val fallback = runCatching {
                runBlocking {
                    cookieResolver.fetchBinaryViaHiddenWebView(
                        url = request.url.toString(),
                        referer = referer
                    )
                }
            }.getOrNull()

            if (fallback != null &&
                fallback.statusCode in 200..299 &&
                fallback.bytes.isNotEmpty() &&
                !fallback.contentType.lowercase().startsWith("text/html") &&
                !fallback.hasInteractiveCaptcha
            ) {
                response.close()
                val resolvedType = fallback.contentType.ifBlank { "image/jpeg" }
                return@Interceptor Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", resolvedType)
                    .body(fallback.bytes.toResponseBody(resolvedType.toMediaTypeOrNull()))
                    .build()
            }

            response
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .dispatcher(dispatcher)
            .cache(Cache(cacheDir, 50L * 1024L * 1024L))
            .addInterceptor(headerInterceptor)
            .addInterceptor(webViewImageFallbackInterceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
