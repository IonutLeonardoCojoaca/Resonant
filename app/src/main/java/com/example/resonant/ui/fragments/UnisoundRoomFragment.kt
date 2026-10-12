package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
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
import com.example.resonant.data.network.UnisoundQueueItemDTO
import com.example.resonant.ui.adapters.UnisoundQueueAdapter
import com.example.resonant.ui.adapters.UnisoundQueueRow
import com.example.resonant.ui.bottomsheets.UnisoundSettingsBottomSheet
import com.example.resonant.ui.bottomsheets.UnisoundShareBottomSheet
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.UnisoundViewModel
import com.example.resonant.unisound.UnisoundConnection
import com.example.resonant.unisound.UnisoundControl
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.unisound.UnisoundRoomState
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The shared room: current song, queue with authors, members and room controls. */
class UnisoundRoomFragment : Fragment(R.layout.fragment_unisound_room) {

    private lateinit var viewModel: UnisoundViewModel
    private lateinit var queueAdapter: UnisoundQueueAdapter

    private lateinit var status: TextView
    private lateinit var cover: ImageView
    private lateinit var songTitle: TextView
    private lateinit var songSubtitle: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var playPause: ImageButton
    private lateinit var previous: ImageButton
    private lateinit var next: ImageButton
    private lateinit var reengage: TextView
    private lateinit var settings: View
    private lateinit var members: ChipGroup
    private lateinit var emptyQueue: TextView
    private lateinit var addButton: View
    private var lastCoverSongId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[UnisoundViewModel::class.java]
        bindViews(view)

        queueAdapter = UnisoundQueueAdapter(onRemove = ::removeItem)
        view.findViewById<RecyclerView>(R.id.unisoundQueue).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = queueAdapter
        }

        view.findViewById<View>(R.id.unisoundBackButton).setOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.unisoundShareButton).setOnClickListener {
            UnisoundShareBottomSheet().show(parentFragmentManager, UnisoundShareBottomSheet.TAG)
        }
        settings.setOnClickListener {
            UnisoundSettingsBottomSheet().show(parentFragmentManager, UnisoundSettingsBottomSheet.TAG)
        }
        view.findViewById<View>(R.id.unisoundLeaveButton).setOnClickListener { confirmLeave() }
        previous.setOnClickListener { viewModel.control(UnisoundControl.PREVIOUS) }
        next.setOnClickListener { viewModel.control(UnisoundControl.NEXT) }
        reengage.setOnClickListener { viewModel.control(UnisoundControl.PLAY) }
        playPause.setOnClickListener { togglePlayback() }
        // Songs are added from their options ("Añadir a la sala"), so this opens search.
        addButton.setOnClickListener { findNavController().navigate(R.id.searchFragment) }

        if (savedInstanceState == null && arguments?.getBoolean(ARG_SHOW_SHARE) == true) {
            UnisoundShareBottomSheet().show(parentFragmentManager, UnisoundShareBottomSheet.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        // The room is over (MainActivity shows the summary or the reason).
                        if (state == null) {
                            if (findNavController().currentDestination?.id == R.id.unisoundRoomFragment) {
                                findNavController().popBackStack()
                            }
                        } else {
                            render(state)
                        }
                    }
                }
                launch {
                    while (true) {
                        viewModel.state.value?.let(::renderProgress)
                        delay(PROGRESS_TICK_MS)
                    }
                }
            }
        }
    }

    private fun bindViews(view: View) {
        status = view.findViewById(R.id.unisoundConnectionStatus)
        cover = view.findViewById(R.id.unisoundCover)
        songTitle = view.findViewById(R.id.unisoundSongTitle)
        songSubtitle = view.findViewById(R.id.unisoundSongSubtitle)
        progress = view.findViewById(R.id.unisoundProgress)
        playPause = view.findViewById(R.id.unisoundPlayPauseButton)
        previous = view.findViewById(R.id.unisoundPreviousButton)
        next = view.findViewById(R.id.unisoundNextButton)
        reengage = view.findViewById(R.id.unisoundReengageButton)
        settings = view.findViewById(R.id.unisoundSettingsButton)
        members = view.findViewById(R.id.unisoundMembers)
        emptyQueue = view.findViewById(R.id.unisoundEmptyQueue)
        addButton = view.findViewById(R.id.unisoundAddButton)
    }

    private fun render(state: UnisoundRoomState) {
        val connected = state.connection == UnisoundConnection.CONNECTED
        status.text = when {
            !connected -> "Reconectando…"
            !state.settings.joinOpen -> "Entrada cerrada"
            else -> state.code?.let { "Código $it" }.orEmpty()
        }
        settings.visibility = if (state.isHost) View.VISIBLE else View.GONE
        addButton.visibility = if (state.canAdd) View.VISIBLE else View.GONE

        val current = state.current
        val song = current?.song
        songTitle.text = song?.title ?: "Nada sonando"
        songSubtitle.text = current?.let { item ->
            listOfNotNull(song?.artistName ?: song?.artists?.joinToString(", ") { it.name }, addedByLabel(state, item))
                .filter(String::isNotBlank)
                .joinToString(" · ")
        }.orEmpty()
        if (song?.id != lastCoverSongId) {
            lastCoverSongId = song?.id
            Glide.with(cover).load(song?.coverUrl ?: song?.imageFileName)
                .placeholder(R.drawable.ic_disc).error(R.drawable.ic_disc).into(cover)
        }

        val controlsEnabled = connected && state.canControl && current != null
        listOf(playPause, previous, next).forEach {
            it.isEnabled = controlsEnabled
            it.alpha = if (controlsEnabled) 1f else DISABLED_ALPHA
        }
        playPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        reengage.visibility = if (state.localHold && state.isPlaying) View.VISIBLE else View.GONE

        renderMembers(state)
        queueAdapter.submitList(state.queue.map { item ->
            UnisoundQueueRow(item, addedByLabel(state, item), state.canRemove(item))
        })
        emptyQueue.visibility = if (current == null && state.queue.isEmpty()) View.VISIBLE else View.GONE
        renderProgress(state)
    }

    private fun renderProgress(state: UnisoundRoomState) {
        val duration = state.currentDurationMs
        progress.progress = if (duration > 0L) {
            (state.expectedPositionMs(viewModel.clock.now()) * 1000L / duration).toInt()
        } else {
            0
        }
    }

    private fun renderMembers(state: UnisoundRoomState) {
        members.removeAllViews()
        state.members.forEach { member ->
            val chip = Chip(requireContext()).apply {
                text = if (member.role == UnisoundRoomState.ROLE_HOST) "${member.name} · anfitrión" else member.name
                isClickable = false
                alpha = if (member.connected) 1f else DISABLED_ALPHA
            }
            members.addView(chip)
        }
    }

    private fun addedByLabel(state: UnisoundRoomState, item: UnisoundQueueItemDTO): String = when {
        item.addedBy != ADDED_BY_USER -> ""
        item.addedByUserId != null && item.addedByUserId == state.myUserId -> "Añadido por ti"
        !item.addedByName.isNullOrBlank() -> "Añadido por ${item.addedByName}"
        else -> ""
    }

    private fun togglePlayback() {
        val state = viewModel.state.value ?: return
        if (state.isPlaying && !state.localHold) {
            viewModel.control(UnisoundControl.PAUSE, state.expectedPositionMs(viewModel.clock.now()))
        } else {
            viewModel.control(UnisoundControl.PLAY)
        }
    }

    private fun removeItem(item: UnisoundQueueItemDTO) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.removeItem(item.itemId)
        }
    }

    private fun confirmLeave() {
        val state = viewModel.state.value ?: return
        val dialog = ResonantDialog(requireContext()).setSection("Escuchar juntos")
        if (state.isHost) {
            dialog.setTitle("¿Qué quieres hacer?")
                .setMessage("Si sales, la música sigue para los demás y otra persona pasa a controlar la sala.")
                .setPositiveButton("Terminar para todos") { endRoom() }
                .setDestructive()
                .setNegativeButton("Salir") { leaveRoom() }
        } else {
            dialog.setTitle("¿Salir de la sala?")
                .setMessage("Tu música se detendrá.")
                .setPositiveButton("Salir") { leaveRoom() }
                .setNegativeButton("Cancelar")
        }
        dialog.show()
    }

    private fun leaveRoom() {
        viewLifecycleOwner.lifecycleScope.launch { viewModel.leaveRoom() }
    }

    private fun endRoom() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.endRoom()
            if (result is UnisoundResult.Failure) {
                showResonantSnackbar(result.message, R.color.errorColor, R.drawable.ic_warning)
            }
        }
    }

    companion object {
        const val ARG_SHOW_SHARE = "showShare"
        private const val ADDED_BY_USER = "user"
        private const val PROGRESS_TICK_MS = 500L
        private const val DISABLED_ALPHA = 0.4f
    }
}
