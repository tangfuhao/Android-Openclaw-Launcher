package com.openclaw.android.di

import android.content.Context
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.gateway.GatewayClient
import com.openclaw.android.proot.FileBridge
import com.openclaw.android.proot.FileDownloader
import com.openclaw.android.proot.OpenClawConfigWriter
import com.openclaw.android.proot.ProotExecutor
import com.openclaw.android.proot.RootfsInstaller
import com.openclaw.android.service.HealthMonitor
import com.openclaw.android.service.ProcessManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePaths(@ApplicationContext context: Context): OpenClawConstants.Paths {
        return OpenClawConstants.Paths(context.filesDir)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager {
        return PreferencesManager(context)
    }

    @Provides
    @Singleton
    fun provideFileDownloader(okHttpClient: OkHttpClient): FileDownloader {
        return FileDownloader(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideOpenClawConfigWriter(
        paths: OpenClawConstants.Paths,
        preferencesManager: PreferencesManager,
    ): OpenClawConfigWriter {
        return OpenClawConfigWriter(paths, preferencesManager)
    }

    @Provides
    @Singleton
    fun provideProotExecutor(
        @ApplicationContext context: Context,
        paths: OpenClawConstants.Paths,
        configWriter: OpenClawConfigWriter,
    ): ProotExecutor {
        return ProotExecutor(context, paths, configWriter)
    }

    @Provides
    @Singleton
    fun provideRootfsInstaller(
        @ApplicationContext context: Context,
        paths: OpenClawConstants.Paths,
        downloader: FileDownloader,
        preferencesManager: PreferencesManager,
    ): RootfsInstaller {
        return RootfsInstaller(context, paths, downloader, preferencesManager)
    }

    @Provides
    @Singleton
    fun provideProcessManager(
        @ApplicationContext context: Context,
        paths: OpenClawConstants.Paths,
        prootExecutor: ProotExecutor,
        configWriter: OpenClawConfigWriter,
    ): ProcessManager {
        return ProcessManager(context, paths, prootExecutor, configWriter)
    }

    @Provides
    @Singleton
    fun provideFileBridge(
        @ApplicationContext context: Context,
        paths: OpenClawConstants.Paths,
    ): FileBridge {
        return FileBridge(context, paths)
    }

    @Provides
    @Singleton
    fun provideGatewayClient(
        okHttpClient: OkHttpClient,
        preferencesManager: PreferencesManager,
        paths: OpenClawConstants.Paths,
    ): GatewayClient {
        return GatewayClient(okHttpClient, preferencesManager, paths)
    }

    @Provides
    @Singleton
    fun provideHealthMonitor(gatewayClient: GatewayClient): HealthMonitor {
        return HealthMonitor(gatewayClient)
    }
}
