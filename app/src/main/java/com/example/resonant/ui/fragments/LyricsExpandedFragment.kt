package com.example.resonant.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.managers.LyricLine
import com.example.resonant.managers.LyricsManager
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.LyricsAdapter
import kotlinx.coroutines.launch

class LyricsExpandedFragment : DialogFragment() {

    companion object {
        private const val ARG_DOMINANT_COLOR = "dominant_color"
        private const val ARG_SONG_ID = "song_id"

        fun newInstance(songId: String, dominantColor: Int): LyricsExpandedFragment {
            return LyricsExpandedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SONG_ID, songId)
                    putInt(ARG_DOMINANT_COLOR, dominantColor)
                }
            }
        }
    }

    private lateinit var expandedRoot: View
    private lateinit var lyricsRecyclerView: RecyclerView
    private lateinit var expandedTopFade: View
    private lateinit var expandedBottomFade: View
    private lateinit var songTitleView: TextView
    private lateinit var songArtistView: TextView
    private lateinit var closeButton: FrameLayout
    private lateinit var blurBackground: ImageView

    private lateinit var lyricsAdapter: LyricsAdapter
    private val lyricsHandler = Handler(Looper.getMainLooper())
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastActiveLine = -1
    private var autoScrollEnabled = true
    private var hasTimedLyrics = false
    private var ignoreUpdatesUntilMs = 0L

    private var musicService: MusicPlaybackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            musicService = (binder as MusicPlaybackService.MusicServiceBinder).getService()
            serviceBound = true
            if (lyricLines.isNotEmpty()) startLyricsSync()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            serviceBound = false
            stopLyricsSync()
        }
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            val service = musicService ?: return

            if (System.currentTimeMillis() < ignoreUpdatesUntilMs) {
                lyricsHandler.postDelayed(this, if (hasTimedLyrics) 350 else 16)
                return
            }

            val positionMs = service.getCurrentPosition().toLong()
            val durationMs = service.getDuration().toLong()
            syncLyricsToPosition(positionMs, durationMs, forceScroll = false)
            lyricsHandler.postDelayed(this, if (hasTimedLyrics) 350 else 16)
        }
    }

    private val reenableAutoScrollRunnable = Runnable { autoScrollEnabled = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setWindowAnimations(R.style.DialogAnimationUpDown)
        Intent(requireContext(), MusicPlaybackService::class.java).also {
            requireContext().bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (lyricLines.isNotEmpty() && serviceBound) startLyricsSync()
    }

    override fun onPause() {
        super.onPause()
        stopLyricsSync()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLyricsSync()
        lyricsHandler.removeCallbacksAndMessages(null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_lyrics_expanded, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        expandedRoot = view.findViewById(R.id.expandedRoot)
        lyricsRecyclerView = view.findViewById(R.id.expandedLyricsRecyclerView)
        expandedTopFade = view.findViewById(R.id.expandedTopFade)
        expandedBottomFade = view.findViewById(R.id.expandedBottomFade)
        songTitleView = view.findViewById(R.id.expandedSongTitle)
        songArtistView = view.findViewById(R.id.expandedSongArtist)
        closeButton = view.findViewById(R.id.expandedCloseButton)
        blurBackground = view.findViewById(R.id.expandedBlurBackground)

        val dominantColor = arguments?.getInt(ARG_DOMINANT_COLOR, Color.BLACK) ?: Color.BLACK
        val songId = arguments?.getString(ARG_SONG_ID) ?: return

        val bgColor = ColorUtils.blendARGB(dominantColor, Color.BLACK, 0.65f)
        expandedRoot.setBackgroundColor(bgColor)
        applyFadeGradients(bgColor)

        PlaybackStateRepository.currentSong?.let { song ->
            songTitleView.text = song.title ?: ""
            songArtistView.text = song.artistName
                ?: song.artists.joinToString(", ") { it.name }
        }

        lyricsAdapter = LyricsAdapter()
        lyricsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        lyricsRecyclerView.adapter = lyricsAdapter
        lyricsRecyclerView.itemAnimator = null
        lyricsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScrollEnabled = false
                    lyricsHandler.removeCallbacks(reenableAutoScrollRunnable)
                    lyricsHandler.postDelayed(reenableAutoScrollRunnable, 3000)
                }
            }
        })

        closeButton.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            lyricLines = LyricsManager.getLyrics(requireContext(), songId)
            hasTimedLyrics = lyricLines.any { it.timeMs >= 0 }
            lastActiveLine = -1
            lyricsAdapter.submitLines(lyricLines)
            lyricsRecyclerView.scrollToPosition(0)
            if (!hasTimedLyrics && lyricLines.isNotEmpty()) {
                lyricsAdapter.clearActiveLine()
                updateLinearLyricsProgress(
                    musicService?.getCurrentPosition()?.toLong() ?: 0L,
                    musicService?.getDuration()?.toLong() ?: 0L
                )
            }
            if (serviceBound && lyricLines.isNotEmpty()) {
                startLyricsSync()
            }
        }
    }

    private fun applyFadeGradients(bgColor: Int) {
        val transparent = Color.TRANSPARENT
        expandedTopFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(bgColor, transparent)
        )
        expandedBottomFade.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(bgColor, transparent)
        )
    }

    private fun startLyricsSync() {
        lyricsHandler.removeCallbacks(syncRunnable)
        lyricsHandler.post(syncRunnable)
    }

    private fun stopLyricsSync() {
        lyricsHandler.removeCallbacks(syncRunnable)
    }

    private fun syncLyricsToPosition(positionMs: Long, durationMs: Long, forceScroll: Boolean) {
        if (lyricLines.isEmpty()) return

        if (hasTimedLyrics) {
            val newIndex = LyricsManager.getCurrentLineIndex(lyricLines, positionMs)
            val shouldScroll = forceScroll || (autoScrollEnabled && newIndex != lastActiveLine)

            if (newIndex != lastActiveLine) {
                lastActiveLine = newIndex
                lyricsAdapter.updateActiveLine(newIndex)
            }

            if (shouldScroll && newIndex >= 0) {
                scrollToActiveLine(newIndex)
            }
        } else {
            updateLinearLyricsProgress(positionMs, durationMs)
        }
    }

    private fun scrollToActiveLine(index: Int) {
        if (!autoScrollEnabled) return
        val lm = lyricsRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val availableHeight = lyricsRecyclerView.height - lyricsRecyclerView.paddingTop - lyricsRecyclerView.paddingBottom
        if (availableHeight <= 0) return
        val centerOffset = lyricsRecyclerView.paddingTop + (availableHeight / 2)
        val itemView = lm.findViewByPosition(index)
        if (itemView != null) {
            val itemCenter = (itemView.top + itemView.bottom) / 2
            lyricsRecyclerView.smoothScrollBy(0, itemCenter - centerOffset)
        } else {
            lm.scrollToPositionWithOffset(index, centerOffset)
        }
    }

    private fun updateLinearLyricsProgress(positionMs: Long, durationMs: Long) {
        if (lyricLines.isEmpty() || durationMs <= 0) return

        val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        if (lastActiveLine != -1) {
            lastActiveLine = -1
            lyricsAdapter.clearActiveLine()
        }

        if (!autoScrollEnabled) return

        lyricsRecyclerView.post {
            val scrollRange = (lyricsRecyclerView.computeVerticalScrollRange() - lyricsRecyclerView.height).coerceAtLeast(0)
            val targetOffset = (scrollRange * progress).toInt()
            val currentOffset = lyricsRecyclerView.computeVerticalScrollOffset()
            if (kotlin.math.abs(targetOffset - currentOffset) <= 1) return@post

            if (kotlin.math.abs(targetOffset - currentOffset) > 120) {
                lyricsRecyclerView.scrollBy(0, targetOffset - currentOffset)
            } else {
                val nextOffset = currentOffset + ((targetOffset - currentOffset) * 0.18f).toInt()
                lyricsRecyclerView.scrollBy(0, nextOffset - currentOffset)
            }
        }
    }
}
