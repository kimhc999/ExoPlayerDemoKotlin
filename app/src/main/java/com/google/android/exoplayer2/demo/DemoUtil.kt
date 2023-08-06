/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.demo

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import org.checkerframework.checker.nullness.qual.MonotonicNonNull
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.Executors

/** Utility methods for the demo app.  */
@UnstableApi
object DemoUtil {
    const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"

    /**
     * Whether the demo application uses Cronet for networking. Note that Cronet does not provide
     * automatic support for cookies (https://github.com/google/ExoPlayer/issues/5975).
     *
     *
     * If set to false, the platform's default network stack is used with a [CookieManager]
     * configured in [.getHttpDataSourceFactory].
     */
    private const val USE_CRONET_FOR_NETWORKING = true
    private const val TAG = "DemoUtil"
    private const val DOWNLOAD_CONTENT_DIRECTORY = "downloads"
    private var dataSourceFactory: @MonotonicNonNull DataSource.Factory? = null
    private var httpDataSourceFactory: @MonotonicNonNull DataSource.Factory? = null
    private var databaseProvider: @MonotonicNonNull DatabaseProvider? = null
    private var downloadDirectory: @MonotonicNonNull File? = null
    private var downloadCache: @MonotonicNonNull Cache? = null
    private lateinit var downloadManager: @MonotonicNonNull DownloadManager
    private lateinit var downloadTracker: @MonotonicNonNull DownloadTracker
    private var downloadNotificationHelper: @MonotonicNonNull DownloadNotificationHelper? = null

    /** Returns whether extension renderers should be used.  */
    fun useExtensionRenderers(): Boolean {
        return BuildConfig.USE_DECODER_EXTENSIONS
    }

    fun buildRenderersFactory(
        context: Context, preferExtensionRenderer: Boolean
    ): RenderersFactory {
        val extensionRendererMode =
            if (useExtensionRenderers()) (if (preferExtensionRenderer) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        return DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(extensionRendererMode)
    }

    @Synchronized
    fun getHttpDataSourceFactory(context: Context): DataSource.Factory {
        if (httpDataSourceFactory == null) {
            if (USE_CRONET_FOR_NETWORKING) {
                val applicationContext = context.applicationContext
                val cronetEngine = CronetUtil.buildCronetEngine(applicationContext)
                if (cronetEngine != null) {
                    httpDataSourceFactory =
                        CronetDataSource.Factory(cronetEngine, Executors.newSingleThreadExecutor())
                }
            }
            if (httpDataSourceFactory == null) {
                // We don't want to use Cronet, or we failed to instantiate a CronetEngine.
                val cookieManager = CookieManager()
                cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                CookieHandler.setDefault(cookieManager)
                httpDataSourceFactory = DefaultHttpDataSource.Factory()
            }
        }
        return httpDataSourceFactory!!
    }

    /** Returns a [DataSource.Factory].  */
    @Synchronized
    fun getDataSourceFactory(context: Context): DataSource.Factory? {
        if (dataSourceFactory == null) {
            val applicationContext = context.applicationContext
            val upstreamFactory =
                DefaultDataSource.Factory(
                    applicationContext,
                    getHttpDataSourceFactory(applicationContext)
                )
            dataSourceFactory =
                buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache(applicationContext))
        }
        return dataSourceFactory
    }

    @Synchronized
    fun getDownloadNotificationHelper(
        context: Context
    ): DownloadNotificationHelper {
        downloadNotificationHelper?.let {
            return it
        } ?: run {
            DownloadNotificationHelper(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID).run {
                downloadNotificationHelper = this
                return this
            }
        }
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        ensureDownloadManagerInitialized(context)
        return downloadManager
    }

    @Synchronized
    fun getDownloadTracker(context: Context): DownloadTracker {
        ensureDownloadManagerInitialized(context)
        return downloadTracker
    }

    @Synchronized
    private fun getDownloadCache(context: Context): Cache {
        downloadCache?.run {
            return this
        } ?: run {
            val downloadContentDirectory =
                File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            SimpleCache(
                downloadContentDirectory, NoOpCacheEvictor(), getDatabaseProvider(context)
            ).run {
                downloadCache = this
                return this
            }
        }
    }

    @Synchronized
    private fun ensureDownloadManagerInitialized(context: Context) {
        if (this::downloadManager.isInitialized.isFalse()) {
            downloadManager = DownloadManager(
                context,
                getDatabaseProvider(context),
                getDownloadCache(context),
                getHttpDataSourceFactory(context),
                Executors.newFixedThreadPool(6)
            )
            downloadTracker =
                DownloadTracker(context, getHttpDataSourceFactory(context), downloadManager)
        }
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        databaseProvider?.let {
            return it
        } ?: run {
            StandaloneDatabaseProvider(context).run {
                databaseProvider = this
                return this
            }
        }
    }

    @Synchronized
    private fun getDownloadDirectory(context: Context): File {
        downloadDirectory?.let {
            return it
        } ?: run {
            context.getExternalFilesDir(null)?.run {
                downloadDirectory = this
                return this
            } ?: run {
                downloadDirectory = context.filesDir
                return context.filesDir
            }
        }
    }

    private fun buildReadOnlyCacheDataSource(
        upstreamFactory: DataSource.Factory, cache: Cache?
    ): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(cache!!)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}