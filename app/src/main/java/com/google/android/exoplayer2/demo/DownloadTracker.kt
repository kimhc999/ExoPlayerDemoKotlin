/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.DialogInterface
import android.net.Uri
import android.os.AsyncTask
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.MediaItem.DrmConfiguration
import com.google.android.exoplayer2.drm.*
import com.google.android.exoplayer2.drm.DrmSession.DrmSessionException
import com.google.android.exoplayer2.offline.*
import com.google.android.exoplayer2.offline.DownloadHelper.LiveContentUnsupportedException
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.util.*
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/** Tracks media that has been downloaded.  */
class DownloadTracker(
    context: Context, dataSourceFactory: DataSource.Factory?, downloadManager: DownloadManager?
) {
    /** Listens for changes in the tracked downloads.  */
    interface Listener {
        /** Called when the tracked downloads changed.  */
        fun onDownloadsChanged()
    }

    private val context: Context
    private val dataSourceFactory: DataSource.Factory?
    private val listeners: CopyOnWriteArraySet<Listener>
    private val downloads: HashMap<Uri, Download>
    private val downloadIndex: DownloadIndex
    private var startDownloadDialogHelper: StartDownloadDialogHelper? = null

    init {
        this.context = context.applicationContext
        this.dataSourceFactory = dataSourceFactory
        listeners = CopyOnWriteArraySet()
        downloads = HashMap()
        downloadIndex = downloadManager!!.downloadIndex
        downloadManager.addListener(DownloadManagerListener())
        loadDownloads()
    }

    fun addListener(listener: Listener?) {
        listeners.add(Preconditions.checkNotNull(listener))
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isDownloaded(mediaItem: MediaItem): Boolean {
        val download = downloads[Preconditions.checkNotNull(mediaItem.localConfiguration).uri]
        return download != null && download.state != Download.STATE_FAILED
    }

    fun getDownloadRequest(uri: Uri): DownloadRequest? {
        val download = downloads[uri]
        return if (download != null && download.state != Download.STATE_FAILED) download.request else null
    }

    fun toggleDownload(
        fragmentManager: FragmentManager?, mediaItem: MediaItem, renderersFactory: RenderersFactory?
    ) {
        val download = downloads[Preconditions.checkNotNull(mediaItem.localConfiguration).uri]
        if (download != null && download.state != Download.STATE_FAILED) {
            DownloadService.sendRemoveDownload(
                context,
                DemoDownloadService::class.java,
                download.request.id,
                false
            )
        } else {
            if (startDownloadDialogHelper != null) {
                startDownloadDialogHelper!!.release()
            }
            startDownloadDialogHelper = StartDownloadDialogHelper(
                fragmentManager,
                DownloadHelper.forMediaItem(
                    context,
                    mediaItem,
                    renderersFactory,
                    dataSourceFactory
                ),
                mediaItem
            )
        }
    }

    private fun loadDownloads() {
        try {
            downloadIndex.getDownloads().use { loadedDownloads ->
                while (loadedDownloads.moveToNext()) {
                    val download = loadedDownloads.download
                    downloads[download.request.uri] = download
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to query downloads", e)
        }
    }

    private inner class DownloadManagerListener : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager, download: Download, finalException: Exception?
        ) {
            downloads[download.request.uri] = download
            for (listener in listeners) {
                listener.onDownloadsChanged()
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            downloads.remove(download.request.uri)
            for (listener in listeners) {
                listener.onDownloadsChanged()
            }
        }
    }

    private inner class StartDownloadDialogHelper(
        private val fragmentManager: FragmentManager?,
        private val downloadHelper: DownloadHelper,
        private val mediaItem: MediaItem
    ) : DownloadHelper.Callback, TrackSelectionDialog.TrackSelectionListener,
        DialogInterface.OnDismissListener {
        private var trackSelectionDialog: TrackSelectionDialog? = null
        private var widevineOfflineLicenseFetchTask: WidevineOfflineLicenseFetchTask? = null
        private var keySetId: ByteArray? = null

        init {
            downloadHelper.prepare(this)
        }

        fun release() {
            downloadHelper.release()
            trackSelectionDialog?.dismiss()
            widevineOfflineLicenseFetchTask?.cancel(false)
        }

        // DownloadHelper.Callback implementation.
        override fun onPrepared(helper: DownloadHelper) {
            val format = getFirstFormatWithDrmInitData(helper)
            if (format == null) {
                onDownloadPrepared(helper)
                return
            }

            // The content is DRM protected. We need to acquire an offline license.
            if (Util.SDK_INT < 18) {
                Toast.makeText(
                    context,
                    R.string.error_drm_unsupported_before_api_18,
                    Toast.LENGTH_LONG
                )
                    .show()
                Log.e(
                    TAG,
                    "Downloading DRM protected content is not supported on API versions below 18"
                )
                return
            }
            // TODO(internal b/163107948): Support cases where DrmInitData are not in the manifest.
            if (!hasSchemaData(format.drmInitData)) {
                Toast.makeText(
                    context,
                    R.string.download_start_error_offline_license,
                    Toast.LENGTH_LONG
                )
                    .show()
                Log.e(
                    TAG,
                    "Downloading content where DRM scheme data is not located in the manifest is not"
                            + " supported"
                )
                return
            }
            widevineOfflineLicenseFetchTask = WidevineOfflineLicenseFetchTask(
                format,
                mediaItem.localConfiguration!!.drmConfiguration,
                dataSourceFactory,  /* dialogHelper= */
                this,
                helper
            )
            widevineOfflineLicenseFetchTask?.execute()
        }

        override fun onPrepareError(helper: DownloadHelper, e: IOException) {
            val isLiveContent = e is LiveContentUnsupportedException
            val toastStringId =
                if (isLiveContent) R.string.download_live_unsupported else R.string.download_start_error
            val logMessage =
                if (isLiveContent) "Downloading live content unsupported" else "Failed to start download"
            Toast.makeText(context, toastStringId, Toast.LENGTH_LONG).show()
            Log.e(TAG, logMessage, e)
        }

        // TrackSelectionListener implementation.
        override fun onTracksSelected(trackSelectionParameters: TrackSelectionParameters) {
            for (periodIndex in 0 until downloadHelper.periodCount) {
                downloadHelper.clearTrackSelections(periodIndex)
                downloadHelper.addTrackSelection(periodIndex, trackSelectionParameters)
            }
            val downloadRequest = buildDownloadRequest()
            if (downloadRequest.streamKeys.isEmpty()) {
                // All tracks were deselected in the dialog. Don't start the download.
                return
            }
            startDownload(downloadRequest)
        }

        // DialogInterface.OnDismissListener implementation.
        override fun onDismiss(dialogInterface: DialogInterface) {
            trackSelectionDialog = null
            downloadHelper.release()
        }
        // Internal methods.
        /**
         * Returns the first [Format] with a non-null [Format.drmInitData] found in the
         * content's tracks, or null if none is found.
         */
        private fun getFirstFormatWithDrmInitData(helper: DownloadHelper): Format? {
            for (periodIndex in 0 until helper.periodCount) {
                val mappedTrackInfo = helper.getMappedTrackInfo(periodIndex)
                for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                    val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                    for (trackGroupIndex in 0 until trackGroups.length) {
                        val trackGroup = trackGroups[trackGroupIndex]
                        for (formatIndex in 0 until trackGroup.length) {
                            val format = trackGroup.getFormat(formatIndex)
                            if (format.drmInitData != null) {
                                return format
                            }
                        }
                    }
                }
            }
            return null
        }

        fun onOfflineLicenseFetched(helper: DownloadHelper, keySetId: ByteArray) {
            this.keySetId = keySetId
            onDownloadPrepared(helper)
        }

        fun onOfflineLicenseFetchedError(e: DrmSessionException) {
            Toast.makeText(
                context,
                R.string.download_start_error_offline_license,
                Toast.LENGTH_LONG
            )
                .show()
            Log.e(TAG, "Failed to fetch offline DRM license", e)
        }

        private fun onDownloadPrepared(helper: DownloadHelper) {
            if (helper.periodCount == 0) {
                Log.d(TAG, "No periods found. Downloading entire stream.")
                startDownload()
                downloadHelper.release()
                return
            }
            val tracks = downloadHelper.getTracks(0)
            if (!TrackSelectionDialog.willHaveContent(tracks)) {
                Log.d(TAG, "No dialog content. Downloading entire stream.")
                startDownload()
                downloadHelper.release()
                return
            }
            trackSelectionDialog =
                TrackSelectionDialog.createForTracksAndParameters( /* titleId= */
                    R.string.exo_download_description,
                    tracks,
                    DownloadHelper.getDefaultTrackSelectorParameters(context),
                    allowAdaptiveSelections = false,
                    allowMultipleOverrides = true,  /* onTracksSelectedListener= */
                    trackSelectionListener = this,
                    onDismissListener = this
                )
            fragmentManager?.let { fragmentManager ->
                trackSelectionDialog?.show(fragmentManager, null)
            }
        }

        /**
         * Returns whether any the [DrmInitData.SchemeData] contained in `drmInitData` has
         * non-null [DrmInitData.SchemeData.data].
         */
        private fun hasSchemaData(drmInitData: DrmInitData?): Boolean {
            for (i in 0 until (drmInitData?.schemeDataCount ?: 0)) {
                if (drmInitData?.get(i)?.hasData().isTrue()) {
                    return true
                }
            }
            return false
        }

        private fun startDownload(downloadRequest: DownloadRequest = buildDownloadRequest()) {
            DownloadService.sendAddDownload(
                context, DemoDownloadService::class.java, downloadRequest, false
            )
        }

        private fun buildDownloadRequest(): DownloadRequest {
            return downloadHelper
                .getDownloadRequest(
                    Util.getUtf8Bytes(Preconditions.checkNotNull(mediaItem.mediaMetadata.title.toString()))
                )
                .copyWithKeySetId(keySetId)
        }

    }

    /** Downloads a Widevine offline license in a background thread.  */
    @RequiresApi(18)
    private class WidevineOfflineLicenseFetchTask(
        private val format: Format,
        private val drmConfiguration: DrmConfiguration?,
        private val dataSourceFactory: DataSource.Factory?,
        private val dialogHelper: StartDownloadDialogHelper,
        private val downloadHelper: DownloadHelper
    ) : AsyncTask<Void?, Void?, Void?>() {
        private var keySetId: ByteArray? = null
        private var drmSessionException: DrmSessionException? = null
        override fun doInBackground(vararg params: Void?): Void? {
            val offlineLicenseHelper = OfflineLicenseHelper.newWidevineInstance(
                drmConfiguration!!.licenseUri.toString(),
                drmConfiguration.forceDefaultLicenseUri,
                dataSourceFactory!!,
                drmConfiguration.licenseRequestHeaders,
                DrmSessionEventListener.EventDispatcher()
            )
            try {
                keySetId = offlineLicenseHelper.downloadLicense(format)
            } catch (e: DrmSessionException) {
                drmSessionException = e
            } finally {
                offlineLicenseHelper.release()
            }
            return null
        }

        override fun onPostExecute(aVoid: Void?) {
            if (drmSessionException != null) {
                dialogHelper.onOfflineLicenseFetchedError(drmSessionException!!)
            } else {
                dialogHelper.onOfflineLicenseFetched(
                    downloadHelper,
                    Preconditions.checkNotNull(keySetId)
                )
            }
        }
    }

    companion object {
        private const val TAG = "DownloadTracker"
    }
}