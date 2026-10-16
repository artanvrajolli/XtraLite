package com.github.andreyasadchy.xtra.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.media3.common.Tracks
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.PlayerSettingsBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlayerSettingsDialog : BottomSheetDialogFragment() {

    companion object {

        private const val TYPE = "type"
        private const val SPEED = "speed"
        private const val VOD_GAMES = "vod_games"

        fun newInstance(type: String?, speedText: String?, vodGames: Boolean): PlayerSettingsDialog {
            return PlayerSettingsDialog().apply {
                arguments = Bundle().apply {
                    putString(TYPE, type)
                    putString(SPEED, speedText)
                    putBoolean(VOD_GAMES, vodGames)
                }
            }
        }
    }

    private var _binding: PlayerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = PlayerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val arguments = requireArguments()
        val type = arguments.getString(TYPE)
        with(binding) {
            if (type != BasePlaybackService.STREAM && requireContext().prefs().getBoolean(C.PLAYER_MENU_SPEED, false)) {
                menuSpeed.visibility = View.VISIBLE
                menuSpeed.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showSpeedDialog()
                    dismiss()
                }
                setSpeed(arguments.getString(SPEED))
            }
            if (type != BasePlaybackService.STREAM) {
                (parentFragment as? PlayerFragment)?.getSkipSilence()?.let { enabled ->
                    menuSkipSilence.visibility = View.VISIBLE
                    menuSkipSilence.isChecked = enabled
                    menuSkipSilence.setOnCheckedChangeListener { _, isChecked ->
                        (parentFragment as? PlayerFragment)?.setSkipSilence(isChecked)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SKIP_SILENCE, isChecked) }
                    }
                }
            }
            if ((parentFragment as? PlayerFragment)?.canWatchFullVideo() == true) {
                menuWatchVideo.visibility = View.VISIBLE
                menuWatchVideo.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.watchFullVideo()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_QUALITY, false)) {
                menuQuality.visibility = View.VISIBLE
                menuQuality.setOnClickListener { dismiss() }
                (parentFragment as? PlayerFragment)?.setQualityText()
            }
            if (type == BasePlaybackService.STREAM) {
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VIEWER_LIST, true)) {
                    menuViewerList.visibility = View.VISIBLE
                    menuViewerList.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.openViewerList()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_FIND_VOD, true)) {
                    menuFindVod.visibility = View.VISIBLE
                    menuFindVod.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.findUnlistedVideo()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_RESTART, false)) {
                    menuRestart.visibility = View.VISIBLE
                    menuRestart.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.restartPlayer()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS, false)) {
                    menuMediaPlaylistTags.visibility = View.VISIBLE
                    menuMediaPlaylistTags.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(true)
                        dismiss()
                    }
                    menuMultivariantPlaylistTags.visibility = View.VISIBLE
                    menuMultivariantPlaylistTags.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(false)
                        dismiss()
                    }
                }
            }
            if (type == BasePlaybackService.VIDEO) {
                if (arguments.getBoolean(VOD_GAMES)) {
                    setVodGames()
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                    (parentFragment as? PlayerFragment)?.checkBookmark()
                }
            }
            if (type != BasePlaybackService.OFFLINE_VIDEO && requireContext().prefs().getBoolean(C.PLAYER_MENU_DOWNLOAD, true)) {
                menuDownload.visibility = View.VISIBLE
                menuDownload.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showDownloadDialog()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_SHARE, true)) {
                menuShare.visibility = View.VISIBLE
                menuShare.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.share()
                    dismiss()
                }
            }
            if (type != BasePlaybackService.CLIP && requireContext().prefs().getBoolean(C.PLAYER_MENU_SLEEP, true)) {
                menuTimer.visibility = View.VISIBLE
                menuTimer.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showSleepTimerDialog()
                    dismiss()
                }
            }
            if ((parentFragment as? PlayerFragment)?.getIsPortrait() == false) {
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_ASPECT, false)) {
                    menuRatio.visibility = View.VISIBLE
                    menuRatio.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.setResizeMode()
                        dismiss()
                    }
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VOLUME, false)) {
                menuVolume.visibility = View.VISIBLE
                menuVolume.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showVolumeDialog()
                    dismiss()
                }
            }
            (parentFragment as? PlayerFragment)?.setSubtitlesButton()
        }
    }

    fun setQuality(text: String?) {
        with(binding) {
            if (!text.isNullOrBlank() && menuQuality.isVisible) {
                qualityValue.visibility = View.VISIBLE
                qualityValue.text = text
                menuQuality.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showQualityDialog()
                    dismiss()
                }
            }
        }
    }

    fun setSpeed(text: String?) {
        with(binding) {
            if (!text.isNullOrBlank() && menuSpeed.isVisible) {
                speedValue.visibility = View.VISIBLE
                speedValue.text = text
            }
        }
    }

    fun setVodGames() {
        with(binding) {
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false)) {
                menuVodGames.visibility = View.VISIBLE
                menuVodGames.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showVodGames()
                    dismiss()
                }
            }
        }
    }

    fun setBookmarkText(isBookmarked: Boolean) {
        with(binding) {
            menuBookmark.visibility = View.VISIBLE
            menuBookmark.text = getString(if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark)
            menuBookmark.setOnClickListener {
                (parentFragment as? PlayerFragment)?.saveBookmark()
                dismiss()
            }
        }
    }

    fun setSubtitles(subtitles: Tracks.Group? = null) {
        with(binding) {
            if (subtitles != null && requireContext().prefs().getBoolean(C.PLAYER_MENU_SUBTITLES, true)) {
                menuSubtitles.visibility = View.VISIBLE
                if (subtitles.isSelected) {
                    menuSubtitles.text = getString(R.string.hide_subtitles)
                    menuSubtitles.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                        dismiss()
                    }
                } else {
                    menuSubtitles.text = getString(R.string.show_subtitles)
                    menuSubtitles.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(true)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                        dismiss()
                    }
                }
            } else {
                menuSubtitles.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
