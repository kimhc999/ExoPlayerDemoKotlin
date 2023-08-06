/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.Resources
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.C.TrackType
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters
import com.google.android.exoplayer2.ui.TrackSelectionView
import com.google.android.material.tabs.TabLayout
import com.google.common.collect.ImmutableList

/** Dialog to select tracks.  */
class TrackSelectionDialog : DialogFragment() {
    /** Called when tracks are selected.  */
    interface TrackSelectionListener {
        /**
         * Called when tracks are selected.
         *
         * @param trackSelectionParameters A [TrackSelectionParameters] representing the selected
         * tracks. Any manual selections are defined by [     ][TrackSelectionParameters.disabledTrackTypes] and [     ][TrackSelectionParameters.overrides].
         */
        fun onTracksSelected(trackSelectionParameters: TrackSelectionParameters)
    }

    private val tabFragments: SparseArray<TrackSelectionViewFragment> = SparseArray()
    private val tabTrackTypes: ArrayList<Int> = ArrayList()
    private var titleId = 0
    private var onClickListener: DialogInterface.OnClickListener? = null
    private var onDismissListener: DialogInterface.OnDismissListener? = null

    init {
        // Retain instance across activity re-creation to prevent losing access to init data.
        retainInstance = true
    }

    private fun init(
        tracks: Tracks,
        trackSelectionParameters: TrackSelectionParameters,
        titleId: Int,
        allowAdaptiveSelections: Boolean,
        allowMultipleOverrides: Boolean,
        onClickListener: DialogInterface.OnClickListener,
        onDismissListener: DialogInterface.OnDismissListener?
    ) {
        this.titleId = titleId
        this.onClickListener = onClickListener
        this.onDismissListener = onDismissListener
        for (i in SUPPORTED_TRACK_TYPES.indices) {
            val trackType = SUPPORTED_TRACK_TYPES[i]
            val trackGroups = ArrayList<Tracks.Group>()
            for (trackGroup in tracks.groups) {
                if (trackGroup.type == trackType) {
                    trackGroups.add(trackGroup)
                }
            }
            if (!trackGroups.isEmpty()) {
                val tabFragment = TrackSelectionViewFragment()
                tabFragment.init(
                    trackGroups,
                    trackSelectionParameters.disabledTrackTypes.contains(trackType),
                    trackSelectionParameters.overrides,
                    allowAdaptiveSelections,
                    allowMultipleOverrides
                )
                tabFragments.put(trackType, tabFragment)
                tabTrackTypes.add(trackType)
            }
        }
    }

    /**
     * Returns whether the disabled option is selected for the specified track type.
     *
     * @param trackType The track type.
     * @return Whether the disabled option is selected for the track type.
     */
    fun getIsDisabled(trackType: Int): Boolean {
        val trackView = tabFragments[trackType]
        return trackView != null && trackView.isDisabled
    }

    /**
     * Returns the selected track overrides for the specified track type.
     *
     * @param trackType The track type.
     * @return The track overrides for the track type.
     */
    fun getOverrides(trackType: Int): Map<TrackGroup, TrackSelectionOverride> {
        val trackView = tabFragments[trackType]
        return trackView.overrides ?: emptyMap()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // We need to own the view to let tab layout work correctly on all API levels. We can't use
        // AlertDialog because it owns the view itself, so we use AppCompatDialog instead, themed using
        // the AlertDialog theme overlay with force-enabled title.
        val dialog = AppCompatDialog(requireActivity(), R.style.TrackSelectionDialogThemeOverlay)
        dialog.setTitle(titleId)
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val dialogView = inflater.inflate(R.layout.track_selection_dialog, container, false)
        val tabLayout = dialogView.findViewById<TabLayout>(R.id.track_selection_dialog_tab_layout)
        val viewPager = dialogView.findViewById<ViewPager>(R.id.track_selection_dialog_view_pager)
        val cancelButton =
            dialogView.findViewById<Button>(R.id.track_selection_dialog_cancel_button)
        val okButton = dialogView.findViewById<Button>(R.id.track_selection_dialog_ok_button)
        viewPager.adapter = FragmentAdapter(childFragmentManager)
        tabLayout.setupWithViewPager(viewPager)
        tabLayout.visibility = if (tabFragments.size() > 1) View.VISIBLE else View.GONE
        cancelButton.setOnClickListener {
            dismiss()
        }
        okButton.setOnClickListener {
            onClickListener?.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dismiss()
        }
        return dialogView
    }

    private inner class FragmentAdapter(fragmentManager: FragmentManager) : FragmentPagerAdapter(
        fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
    ) {
        override fun getItem(position: Int): Fragment {
            return tabFragments[tabTrackTypes[position]]
        }

        override fun getCount(): Int {
            return tabTrackTypes.size
        }

        override fun getPageTitle(position: Int): CharSequence {
            return getTrackTypeString(resources, tabTrackTypes[position])
        }
    }

    /** Fragment to show a track selection in tab of the track selection dialog.  */
    class TrackSelectionViewFragment : Fragment(), TrackSelectionView.TrackSelectionListener {
        private var trackGroups: List<Tracks.Group>? = null
        private var allowAdaptiveSelections = false
        private var allowMultipleOverrides = false

        /* package */
        var isDisabled = false

        /* package */
        var overrides: Map<TrackGroup, TrackSelectionOverride>? = null

        init {
            // Retain instance across activity re-creation to prevent losing access to init data.
            retainInstance = true
        }

        fun init(
            trackGroups: List<Tracks.Group>,
            isDisabled: Boolean,
            overrides: Map<TrackGroup, TrackSelectionOverride>,
            allowAdaptiveSelections: Boolean,
            allowMultipleOverrides: Boolean
        ) {
            this.trackGroups = trackGroups
            this.isDisabled = isDisabled
            this.allowAdaptiveSelections = allowAdaptiveSelections
            this.allowMultipleOverrides = allowMultipleOverrides
            // TrackSelectionView does this filtering internally, but we need to do it here as well to
            // handle the case where the TrackSelectionView is never created.
            this.overrides = HashMap(
                TrackSelectionView.filterOverrides(overrides, trackGroups, allowMultipleOverrides)
            )
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val rootView = inflater.inflate(
                R.layout.exo_track_selection_dialog, container,  false
            )
            val trackSelectionView =
                rootView.findViewById<TrackSelectionView>(R.id.exo_track_selection_view)
            trackSelectionView.setShowDisableOption(true)
            trackSelectionView.setAllowMultipleOverrides(allowMultipleOverrides)
            trackSelectionView.setAllowAdaptiveSelections(allowAdaptiveSelections)
            trackSelectionView.init(
                trackGroups!!,
                isDisabled,
                overrides!!,
                null,
                this
            )
            return rootView
        }

        override fun onTrackSelectionChanged(
            isDisabled: Boolean, overrides: Map<TrackGroup, TrackSelectionOverride>
        ) {
            this.isDisabled = isDisabled
            this.overrides = overrides
        }
    }

    companion object {
        val SUPPORTED_TRACK_TYPES =
            ImmutableList.of(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)

        /**
         * Returns whether a track selection dialog will have content to display if initialized with the
         * specified [Player].
         */
        fun willHaveContent(player: Player?): Boolean {
            return willHaveContent(player!!.currentTracks)
        }

        /**
         * Returns whether a track selection dialog will have content to display if initialized with the
         * specified [Tracks].
         */
        fun willHaveContent(tracks: Tracks): Boolean {
            for (trackGroup in tracks.groups) {
                if (SUPPORTED_TRACK_TYPES.contains(trackGroup.type)) {
                    return true
                }
            }
            return false
        }

        /**
         * Creates a dialog for a given [Player], whose parameters will be automatically updated
         * when tracks are selected.
         *
         * @param player The [Player].
         * @param onDismissListener A [DialogInterface.OnDismissListener] to call when the dialog is
         * dismissed.
         */
        fun createForPlayer(
            player: Player?, onDismissListener: DialogInterface.OnDismissListener?
        ): TrackSelectionDialog {
            return createForTracksAndParameters(
                R.string.track_selection_title,
                player!!.currentTracks,
                player.trackSelectionParameters,
                allowAdaptiveSelections = true,
                allowMultipleOverrides = false,
                trackSelectionListener = object : TrackSelectionListener {
                    override fun onTracksSelected(trackSelectionParameters: TrackSelectionParameters) {
                        player.trackSelectionParameters = trackSelectionParameters
                    }
                },
                onDismissListener = onDismissListener
            )
        }

        /**
         * Creates a dialog for given [Tracks] and [TrackSelectionParameters].
         *
         * @param titleId The resource id of the dialog title.
         * @param tracks The [Tracks] describing the tracks to display.
         * @param trackSelectionParameters The initial [TrackSelectionParameters].
         * @param allowAdaptiveSelections Whether adaptive selections (consisting of more than one track)
         * can be made.
         * @param allowMultipleOverrides Whether tracks from multiple track groups can be selected.
         * @param trackSelectionListener Called when tracks are selected.
         * @param onDismissListener [DialogInterface.OnDismissListener] called when the dialog is
         * dismissed.
         */
        fun createForTracksAndParameters(
            titleId: Int,
            tracks: Tracks,
            trackSelectionParameters: TrackSelectionParameters,
            allowAdaptiveSelections: Boolean,
            allowMultipleOverrides: Boolean,
            trackSelectionListener: TrackSelectionListener,
            onDismissListener: DialogInterface.OnDismissListener?
        ): TrackSelectionDialog {
            val trackSelectionDialog = TrackSelectionDialog()
            trackSelectionDialog.init(
                tracks,
                trackSelectionParameters,
                titleId,
                allowAdaptiveSelections,
                allowMultipleOverrides,  /* onClickListener= */
                { _: DialogInterface?, _: Int ->
                    val builder = trackSelectionParameters.buildUpon()
                    for (i in SUPPORTED_TRACK_TYPES.indices) {
                        val trackType = SUPPORTED_TRACK_TYPES[i]
                        builder.setTrackTypeDisabled(
                            trackType,
                            trackSelectionDialog.getIsDisabled(trackType)
                        )
                        builder.clearOverridesOfType(trackType)
                        val overrides = trackSelectionDialog.getOverrides(trackType)
                        for (override in overrides.values) {
                            builder.addOverride(override)
                        }
                    }
                    trackSelectionListener.onTracksSelected(builder.build())
                },
                onDismissListener
            )
            return trackSelectionDialog
        }

        private fun getTrackTypeString(resources: Resources, trackType: @TrackType Int): String {
            return when (trackType) {
                C.TRACK_TYPE_VIDEO -> resources.getString(R.string.exo_track_selection_title_video)
                C.TRACK_TYPE_AUDIO -> resources.getString(R.string.exo_track_selection_title_audio)
                C.TRACK_TYPE_TEXT -> resources.getString(R.string.exo_track_selection_title_text)
                else -> throw IllegalArgumentException()
            }
        }
    }
}