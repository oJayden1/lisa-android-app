package com.cosmicoppai.lisa.di

import android.content.Context
import androidx.room.Room
import com.cosmicoppai.lisa.data.local.LiSADatabase
import com.cosmicoppai.lisa.data.local.dao.DownloadDao
import com.cosmicoppai.lisa.data.local.dao.ReadlistDao
import com.cosmicoppai.lisa.data.local.dao.SiteStateDao
import com.cosmicoppai.lisa.data.local.dao.WatchlistDao
import com.cosmicoppai.lisa.data.remote.DynamicCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
