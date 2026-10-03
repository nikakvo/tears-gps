package io.github.jqssun.gpssetter.module

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.module.util.ApplicationScope
import io.github.jqssun.gpssetter.room.AppDatabase
import io.github.jqssun.gpssetter.room.FavoriteDao
import io.github.jqssun.gpssetter.update.GitHubService
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import io.github.jqssun.gpssetter.BuildConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule{

    @Singleton
    @Provides
    fun createGitHubService(): Retrofit =
        Retrofit.Builder()
            // No repo (personal Google Maps build): a neutral base URL that is never called.
            .baseUrl(
                if (BuildConfig.UPDATE_REPO.isEmpty()) "https://api.github.com/"
                else "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/"
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    fun provideDownloadManger(application: Application) =
        application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager


    @Singleton
    @Provides
    fun provideGithubService(retrofit: Retrofit): GitHubService =
        retrofit.create(GitHubService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(application: Application, callback: AppDatabase.Callback)
            = Room.databaseBuilder(application, AppDatabase::class.java, "user_database")
        .allowMainThreadQueries()
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(callback)
        .build()


    @Singleton
    @Provides
    fun providesUserDao(favoriteDatabase: AppDatabase) : FavoriteDao =
        favoriteDatabase.favoriteDao()

    @Singleton
    @Provides
    fun provideSettingRepo() : PrefManager =
        PrefManager

    @ApplicationScope
    @Provides
    @Singleton
    fun providesApplicationScope() = CoroutineScope(SupervisorJob())

}
