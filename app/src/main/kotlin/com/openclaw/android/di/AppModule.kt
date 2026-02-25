package com.openclaw.android.di

import android.content.Context
import com.openclaw.android.bootstrap.BootstrapDownloader
import com.openclaw.android.bootstrap.BootstrapInstaller
import com.openclaw.android.bootstrap.EnvironmentSetup
import com.openclaw.android.core.OpenClawConstants
import com.openclaw.android.data.PreferencesManager
import com.openclaw.android.gateway.GatewayClient
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
            .readTimeout(0, TimeUnit.SECONDS) // no timeout for WebSocket
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
    fun provideEnvironmentSetup(paths: OpenClawConstants.Paths): EnvironmentSetup {
        return EnvironmentSetup(paths)
    }

    @Provides
    @Singleton
    fun provideBootstrapDownloader(okHttpClient: OkHttpClient): BootstrapDownloader {
        return BootstrapDownloader(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideBootstrapInstaller(
        @ApplicationContext context: Context,
        paths: OpenClawConstants.Paths,
        downloader: BootstrapDownloader,
        preferencesManager: PreferencesManager,
    ): BootstrapInstaller {
        return BootstrapInstaller(context, paths, downloader, preferencesManager)
    }

    @Provides
    @Singleton
    fun provideProcessManager(
        @ApplicationContext context: Context,
        paths: OpenClawConstants.Paths,
        environmentSetup: EnvironmentSetup,
    ): ProcessManager {
        return ProcessManager(context, paths, environmentSetup)
    }

    @Provides
    @Singleton
    fun provideGatewayClient(okHttpClient: OkHttpClient): GatewayClient {
        return GatewayClient(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideHealthMonitor(gatewayClient: GatewayClient): HealthMonitor {
        return HealthMonitor(gatewayClient)
    }
}
