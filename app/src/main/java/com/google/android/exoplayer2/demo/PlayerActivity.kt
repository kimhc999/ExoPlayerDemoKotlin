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

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Pair
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.MediaItem.AdsConfiguration
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.drm.DefaultDrmSessionManagerProvider
import com.google.android.exoplayer2.drm.FrameworkMediaDrm
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader
import com.google.android.exoplayer2.ext.ima.ImaServerSideAdInsertionMediaSource
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException
import com.google.android.exoplayer2.offline.DownloadRequest
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ads.AdsLoader
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.ui.StyledPlayerView.ControllerVisibilityListener
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.util.DebugTextViewHelper
import com.google.android.exoplayer2.util.ErrorMessageProvider
import com.google.android.exoplayer2.util.EventLogger
import com.google.android.exoplayer2.util.Util
import org.checkerframework.checker.nullness.qual.MonotonicNonNull
import kotlin.math.max

/** An activity that plays media using [ExoPlayer].  */
class PlayerActivity : AppCompatActivity(), View.OnClickListener, ControllerVisibilityListener {
    private var playerView: StyledPlayerView? = null
    private var debugRootView: LinearLayout? = null
    private var debugTextView: TextView? = null
    private var player: ExoPlayer? = null
    private var isShowingTrackSelectionDialog = false
    private var selectTracksButton: Button? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private var mediaItems: List<MediaItem>? = null
    private var trackSelectionParameters: TrackSelectionParameters? = null
    private var debugViewHelper: DebugTextViewHelper? = null
    private var lastSeenTracks: Tracks? = null
    private var startAutoPlay = false
    private var startItemIndex = 0
    private var startPosition: Long = 0

    // For ad playback only.
    private var clientSideAdsLoader: AdsLoader? = null

    // TODO: Annotate this and serverSideAdsLoaderState below with @OptIn when it can be applied to
    // fields (needs http://r.android.com/2004032 to be released into a version of
    // androidx.annotation:annotation-experimental).
    private var serverSideAdsLoader: ImaServerSideAdInsertionMediaSource.AdsLoader? = null
    private var serverSideAdsLoaderState: @MonotonicNonNull ImaServerSideAdInsertionMediaSource.AdsLoader.State? =
        null

    // Activity lifecycle.
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataSourceFactory = DemoUtil.getDataSourceFactory(this)
        setContentView()
        debugRootView = findViewById(R.id.controls_root)
        debugTextView = findViewById(R.id.debug_text_view)
        selectTracksButton = findViewById(R.id.select_tracks_button)
        selectTracksButton?.setOnClickListener(this)
        playerView = findViewById(R.id.player_view)
        playerView?.setControllerVisibilityListener(this)
        playerView?.setErrorMessageProvider(PlayerErrorMessageProvider())
        playerView?.requestFocus()
        if (savedInstanceState != null) {
            trackSelectionParameters = TrackSelectionParameters.fromBundle(
                savedInstanceState.getBundle(KEY_TRACK_SELECTION_PARAMETERS)!!
            )
            startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY)
            startItemIndex = savedInstanceState.getInt(KEY_ITEM_INDEX)
            startPosition = savedInstanceState.getLong(KEY_POSITION)
            restoreServerSideAdsLoaderState(savedInstanceState)
        } else {
            trackSelectionParameters = TrackSelectionParameters.Builder(this).build()
            clearStartPosition()
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        releasePlayer()
        releaseClientSideAdsLoader()
        clearStartPosition()
        setIntent(intent)
    }

    public override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            initializePlayer()
            if (playerView != null) {
                playerView!!.onResume()
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23 || player == null) {
            initializePlayer()
            if (playerView != null) {
                playerView!!.onResume()
            }
        }
    }

    public override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT <= 23) {
            if (playerView != null) {
                playerView!!.onPause()
            }
            releasePlayer()
        }
    }

    public override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) {
            if (playerView != null) {
                playerView!!.onPause()
            }
            releasePlayer()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        releaseClientSideAdsLoader()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size == 0) {
            // Empty results are triggered if a permission is requested while another request was already
            // pending and can be safely ignored in this case.
            return
        }
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializePlayer()
        } else {
            showToast(R.string.storage_permission_denied)
            finish()
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        updateTrackSelectorParameters()
        updateStartPosition()
        outState.putBundle(KEY_TRACK_SELECTION_PARAMETERS, trackSelectionParameters!!.toBundle())
        outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay)
        outState.putInt(KEY_ITEM_INDEX, startItemIndex)
        outState.putLong(KEY_POSITION, startPosition)
        saveServerSideAdsLoaderState(outState)
    }

    // Activity input
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // See whether the player view wants to handle media or DPAD keys events.
        return playerView!!.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // OnClickListener methods
    override fun onClick(view: View) {
        if (view === selectTracksButton && !isShowingTrackSelectionDialog
            && TrackSelectionDialog.willHaveContent(player)
        ) {
            isShowingTrackSelectionDialog = true
            val trackSelectionDialog: TrackSelectionDialog =
                TrackSelectionDialog.createForPlayer(
                    player
                )  /* onDismissListener= */
                {
                    isShowingTrackSelectionDialog = false
                }
            trackSelectionDialog.show(supportFragmentManager, null)
        }
    }

    // StyledPlayerView.ControllerVisibilityListener implementation
    override fun onVisibilityChanged(visibility: Int) {
        debugRootView?.visibility = visibility
    }

    // Internal methods
    private fun setContentView() {
        setContentView(R.layout.player_activity)
    }

    /**
     * @return Whether initialization was successful.
     */
    private fun initializePlayer(): Boolean {
        if (player == null) {
            val intent = intent
            mediaItems = createMediaItems(intent)
            if (mediaItems!!.isEmpty()) {
                return false
            }
            lastSeenTracks = Tracks.EMPTY
            val playerBuilder = ExoPlayer.Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
            setRenderersFactory(
                playerBuilder,
                intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false)
            )
            player = playerBuilder.build()
            player?.trackSelectionParameters = trackSelectionParameters!!
            player?.addListener(PlayerEventListener())
            player?.addAnalyticsListener(EventLogger())
            player?.setAudioAttributes(AudioAttributes.DEFAULT, true)
            player?.playWhenReady = startAutoPlay
            playerView?.player = player
            configurePlayerWithServerSideAdsLoader()
            debugViewHelper = DebugTextViewHelper(player!!, debugTextView!!)
            debugViewHelper?.start()
        }
        val haveStartPosition = startItemIndex != C.INDEX_UNSET
        if (haveStartPosition) {
            player?.seekTo(startItemIndex, startPosition)
        }
        player?.setMediaItems(mediaItems!!, haveStartPosition.isFalse())
        player?.prepare()
        updateButtonVisibility()
        return true
    }

    private fun createMediaSourceFactory(): MediaSource.Factory {
        val drmSessionManagerProvider = DefaultDrmSessionManagerProvider()
        drmSessionManagerProvider.setDrmHttpDataSourceFactory(
            DemoUtil.getHttpDataSourceFactory(this)
        )
        val serverSideAdLoaderBuilder =
            ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(this, playerView!!)
        if (serverSideAdsLoaderState != null) {
            serverSideAdLoaderBuilder.setAdsLoaderState(serverSideAdsLoaderState!!)
        }
        serverSideAdsLoader = serverSideAdLoaderBuilder.build()
        val imaServerSideAdInsertionMediaSourceFactory =
            ImaServerSideAdInsertionMediaSource.Factory(
                serverSideAdsLoader!!,
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory!!)
            )
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory!!)
            .setDrmSessionManagerProvider(drmSessionManagerProvider)
            .setLocalAdInsertionComponents(
                { adsConfiguration: AdsConfiguration -> getClientSideAdsLoader() },
                playerView!!
            )
            .setServerSideAdInsertionMediaSourceFactory(imaServerSideAdInsertionMediaSourceFactory)
    }

    private fun setRenderersFactory(
        playerBuilder: ExoPlayer.Builder, preferExtensionDecoders: Boolean
    ) {
        val renderersFactory =
            DemoUtil.buildRenderersFactory(this, preferExtensionDecoders)
        playerBuilder.setRenderersFactory(renderersFactory)
    }

    private fun configurePlayerWithServerSideAdsLoader() {
        player?.let {
            serverSideAdsLoader?.setPlayer(it)
        }
    }

    private fun createMediaItems(intent: Intent): List<MediaItem> {
        val action = intent.action
        val actionIsListView = IntentUtil.ACTION_VIEW_LIST == action
        if (!actionIsListView && IntentUtil.ACTION_VIEW != action) {
            showToast(getString(R.string.unexpected_intent_action, action))
            finish()
            return emptyList()
        }
        val mediaItems = createMediaItems(intent, DemoUtil.getDownloadTracker(this))
        for (i in mediaItems.indices) {
            val mediaItem = mediaItems[i]
            if (!Util.checkCleartextTrafficPermitted(mediaItem)) {
                showToast(R.string.error_cleartext_not_permitted)
                finish()
                return emptyList()
            }
            if (Util.maybeRequestReadExternalStoragePermission(this, mediaItem)) {
                // The player will be reinitialized if the permission is granted.
                return emptyList()
            }
            val drmConfiguration = mediaItem.localConfiguration!!.drmConfiguration
            if (drmConfiguration != null) {
                if (Build.VERSION.SDK_INT < 18) {
                    showToast(R.string.error_drm_unsupported_before_api_18)
                    finish()
                    return emptyList<MediaItem>()
                } else if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.scheme)) {
                    showToast(R.string.error_drm_unsupported_scheme)
                    finish()
                    return emptyList<MediaItem>()
                }
            }
        }
        return mediaItems
    }

    private fun getClientSideAdsLoader(): AdsLoader {
        // The ads loader is reused for multiple playbacks, so that ad playback can resume.
        if (clientSideAdsLoader == null) {
            clientSideAdsLoader = ImaAdsLoader.Builder(this).build()
        }
        clientSideAdsLoader?.setPlayer(player)
        return clientSideAdsLoader!!
    }

    private fun releasePlayer() {
        if (player != null) {
            updateTrackSelectorParameters()
            updateStartPosition()
            releaseServerSideAdsLoader()
            debugViewHelper?.stop()
            debugViewHelper = null
            player?.release()
            player = null
            playerView!!.player = null
            mediaItems = emptyList()
        }
        if (clientSideAdsLoader != null) {
            clientSideAdsLoader?.setPlayer(null)
        } else {
            playerView?.adViewGroup?.removeAllViews()
        }
    }

    private fun releaseServerSideAdsLoader() {
        serverSideAdsLoaderState = serverSideAdsLoader!!.release()
        serverSideAdsLoader = null
    }

    private fun releaseClientSideAdsLoader() {
        if (clientSideAdsLoader != null) {
            clientSideAdsLoader?.release()
            clientSideAdsLoader = null
            playerView?.adViewGroup?.removeAllViews()
        }
    }

    private fun saveServerSideAdsLoaderState(outState: Bundle) {
        if (serverSideAdsLoaderState != null) {
            outState.putBundle(
                KEY_SERVER_SIDE_ADS_LOADER_STATE,
                serverSideAdsLoaderState?.toBundle()
            )
        }
    }

    private fun restoreServerSideAdsLoaderState(savedInstanceState: Bundle) {
        val adsLoaderStateBundle = savedInstanceState.getBundle(KEY_SERVER_SIDE_ADS_LOADER_STATE)
        if (adsLoaderStateBundle != null) {
            serverSideAdsLoaderState =
                ImaServerSideAdInsertionMediaSource.AdsLoader.State.CREATOR.fromBundle(
                    adsLoaderStateBundle
                )
        }
    }

    private fun updateTrackSelectorParameters() {
        player?.trackSelectionParameters?.let {
            trackSelectionParameters = it
        }
    }

    private fun updateStartPosition() {
        player?.let {
            startAutoPlay = it.playWhenReady
            startItemIndex = it.currentMediaItemIndex
            startPosition = max(0, it.contentPosition)
        }
    }

    private fun clearStartPosition() {
        startAutoPlay = true
        startItemIndex = C.INDEX_UNSET
        startPosition = C.TIME_UNSET
    }

    // User controls
    private fun updateButtonVisibility() {
        selectTracksButton!!.isEnabled =
            player != null && TrackSelectionDialog.willHaveContent(player)
    }

    private fun showControls() {
        debugRootView?.visibility = View.VISIBLE
    }

    private fun showToast(messageId: Int) {
        showToast(getString(messageId))
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
            if (playbackState == Player.STATE_ENDED) {
                showControls()
            }
            updateButtonVisibility()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition()
                player?.prepare()
            } else {
                updateButtonVisibility()
                showControls()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateButtonVisibility()
            if (tracks === lastSeenTracks) {
                return
            }
            if (tracks.containsType(C.TRACK_TYPE_VIDEO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)
            ) {
                showToast(R.string.error_unsupported_video)
            }
            if (tracks.containsType(C.TRACK_TYPE_AUDIO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, true)
            ) {
                showToast(R.string.error_unsupported_audio)
            }
            lastSeenTracks = tracks
        }
    }

    private inner class PlayerErrorMessageProvider : ErrorMessageProvider<PlaybackException> {
        override fun getErrorMessage(e: PlaybackException): Pair<Int, String> {
            var errorString = getString(R.string.error_generic)
            val cause = e.cause
            if (cause is DecoderInitializationException) {
                // Special case for decoder initialization failures.
                errorString = if (cause.codecInfo == null) {
                    if (cause.cause is DecoderQueryException) {
                        getString(R.string.error_querying_decoders)
                    } else if (cause.secureDecoderRequired) {
                        getString(
                            R.string.error_no_secure_decoder,
                            cause.mimeType
                        )
                    } else {
                        getString(
                            R.string.error_no_decoder,
                            cause.mimeType
                        )
                    }
                } else {
                    getString(
                        R.string.error_instantiating_decoder,
                        cause.codecInfo?.name
                    )
                }
            }
            return Pair.create(0, errorString)
        }
    }

    companion object {
        // Saved instance state keys.
        private const val KEY_TRACK_SELECTION_PARAMETERS = "track_selection_parameters"
        private const val KEY_SERVER_SIDE_ADS_LOADER_STATE = "server_side_ads_loader_state"
        private const val KEY_ITEM_INDEX = "item_index"
        private const val KEY_POSITION = "position"
        private const val KEY_AUTO_PLAY = "auto_play"
        private fun createMediaItems(
            intent: Intent,
            downloadTracker: DownloadTracker
        ): List<MediaItem> {
            val mediaItems: MutableList<MediaItem> = ArrayList()
            for (item in IntentUtil.createMediaItemsFromIntent(intent)) {
                mediaItems.add(
                    maybeSetDownloadProperties(
                        item, downloadTracker.getDownloadRequest(item.localConfiguration!!.uri)
                    )
                )
            }
            return mediaItems
        }

        private fun maybeSetDownloadProperties(
            item: MediaItem, downloadRequest: DownloadRequest?
        ): MediaItem {
            if (downloadRequest == null) {
                return item
            }
            val builder = item.buildUpon()
            builder
                .setMediaId(downloadRequest.id)
                .setUri(downloadRequest.uri)
                .setCustomCacheKey(downloadRequest.customCacheKey)
                .setMimeType(downloadRequest.mimeType)
                .setStreamKeys(downloadRequest.streamKeys)
            val drmConfiguration = item.localConfiguration!!.drmConfiguration
            if (drmConfiguration != null) {
                builder.setDrmConfiguration(
                    drmConfiguration.buildUpon().setKeySetId(downloadRequest.keySetId).build()
                )
            }
            return builder.build()
        }
    }
}