package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.live.LiveScreenButton
import com.example.resonant.live.LiveScreenState
import com.example.resonant.live.LiveScreenStateBuilder
import com.example.resonant.ui.adapters.LiveScheduleAdapter
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.LiveViewModel
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.utils.Utils
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Resonant Live: one channel's broadcast. The listener can only enter and leave. */
class LiveChannelFragment : Fragment() {

    private lateinit var viewModel: LiveViewModel
    private val scheduleAdapter = LiveScheduleAdapter()

    private lateinit var channelName: TextView
    private lateinit var badge: View
    private lateinit var blockTitle: TextView
    private lateinit var cover: ImageView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var progressRow: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var message: TextView
    private lateinit var upNextLabel: View
    private lateinit var upNext: TextView
    private lateinit var actionButton: MaterialButton
    private var shownImageUrl: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_live_channel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        bindViews(view)
        view.findViewById<RecyclerView>(R.id.liveSchedule).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = scheduleAdapter
        }
        view.findViewById<View>(R.id.liveBackButton).setOnClickListener { findNavController().popBackStack() }
        actionButton.setOnClickListener { onActionClick() }

        val slug = requireArguments().getString(ARG_SLUG) ?: run {
            findNavController().popBackStack()
            return
        }
        viewModel.open(slug)

        viewModel.screen.observe(viewLifecycleOwner) { state -> state?.let(::render) }
        viewModel.message.observe(viewLifecycleOwner) { text ->
            if (text != null) {
                showResonantSnackbar(text, R.color.errorColor, R.drawable.ic_error)
                viewModel.consumeMessage()
            }
        }
        viewModel.close.observe(viewLifecycleOwner) { close ->
            if (close && findNavController().currentDestination?.id == R.id.liveChannelFragment) {
                findNavController().popBackStack()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.render()
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun bindViews(view: View) {
        channelName = view.findViewById(R.id.liveChannelName)
        badge = view.findViewById(R.id.liveBadge)
        blockTitle = view.findViewById(R.id.liveBlockTitle)
        cover = view.findViewById(R.id.liveCover)
        songTitle = view.findViewById(R.id.liveSongTitle)
        songArtist = view.findViewById(R.id.liveSongArtist)
        progressRow = view.findViewById(R.id.liveProgressRow)
        progress = view.findViewById(R.id.liveProgress)
        elapsed = view.findViewById(R.id.liveElapsed)
        duration = view.findViewById(R.id.liveDuration)
        message = view.findViewById(R.id.liveMessage)
        upNextLabel = view.findViewById(R.id.liveUpNextLabel)
        upNext = view.findViewById(R.id.liveUpNext)
        actionButton = view.findViewById(R.id.liveActionButton)
    }

    private fun render(state: LiveScreenState) {
        channelName.text = state.channelName
        badge.isVisible = state.onAir
        blockTitle.text = state.blockTitle.orEmpty()
        blockTitle.isVisible = state.blockTitle != null

        songTitle.isVisible = state.onAir
        songArtist.isVisible = state.onAir
        songTitle.text = state.title ?: getString(R.string.live_song_unavailable)
        songArtist.text = state.artist.orEmpty()
        renderCover(state.imageUrl)

        progressRow.isVisible = state.onAir && state.durationMs > 0L
        if (state.durationMs > 0L) {
            progress.progress = (state.positionMs * 1000L / state.durationMs).toInt().coerceIn(0, 1000)
            elapsed.text = Utils.formatTime(state.positionMs.toInt())
            duration.text = Utils.formatTime(state.durationMs.toInt())
        }

        val text = state.countdownMs
            ?.let { getString(R.string.live_starts_in, LiveScreenStateBuilder.formatCountdown(it)) }
            ?: state.message
        message.text = text.orEmpty()
        message.isVisible = text != null

        upNext.text = state.upNext.orEmpty()
        upNext.isVisible = state.upNext != null
        upNextLabel.isVisible = state.upNext != null

        actionButton.isVisible = state.button != LiveScreenButton.NONE
        actionButton.setText(if (state.button == LiveScreenButton.LEAVE) R.string.live_leave else R.string.live_listen)
        scheduleAdapter.submitList(state.rows)
    }

    private fun renderCover(imageUrl: String?) {
        if (imageUrl == shownImageUrl) return
        shownImageUrl = imageUrl
        if (imageUrl == null) {
            Glide.with(this).clear(cover)
        } else {
            Glide.with(this).load(imageUrl).centerCrop().into(cover)
        }
    }

    private fun onActionClick() {
        if (viewModel.isListeningHere()) {
            viewModel.leave()
            return
        }
        val unisound = UnisoundManager.get(requireContext())
        if (unisound.state.value == null) {
            viewModel.listen()
            return
        }
        ResonantDialog(requireContext())
            .setSection("Resonant Live")
            .setTitle(getString(R.string.live_leave_room_title))
            .setMessage(getString(R.string.live_leave_room_message))
            .setPositiveButton(getString(R.string.live_leave_room_confirm)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    unisound.leaveRoom()
                    viewModel.listen()
                }
            }
            .setNegativeButton("Cancelar")
            .show()
    }

    companion object {
        const val ARG_SLUG = "slug"
        private const val TICK_MS = 500L
    }
}
