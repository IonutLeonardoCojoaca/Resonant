package com.example.resonant.ui.bottomsheets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.playback.PlaybackConnectRepository
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.data.network.PlaybackConnectQueueItemDTO
import com.example.resonant.data.network.PlaybackConnectStateDTO
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.PlaybackDeviceAdapter
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackDevicesBottomSheet : ResonantBottomSheetDialogFragment() {
    private val repository by lazy {
        PlaybackConnectRepository.get(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(
        R.layout.bottom_sheet_playback_devices,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.devicesList)
        val loading = view.findViewById<LinearProgressIndicator>(
            R.id.loadingIndicator
        )
        val error = view.findViewById<TextView>(R.id.errorText)
        val adapter = PlaybackDeviceAdapter(repository.localDeviceId) { device ->
            viewLifecycleOwner.lifecycleScope.launch {
                loading.visibility = View.VISIBLE
                // Build a fresh playback state snapshot to send with the
                // transfer request so the backend knows EXACTLY which song
                // and position to relay to the target device.
                repository.forceFullQueueUpload()
                requireContext().startService(
                    Intent(requireContext(), MusicPlaybackService::class.java).apply {
                        action = MusicPlaybackService.ACTION_CONNECT_SYNC
                    }
                )
                kotlinx.coroutines.delay(500)

                // Capture current playback state from the service for the
                // transfer payload (the backend should use this directly).
                val currentPlayback = buildTransferPlaybackState()

                val result = repository.transferTo(device.deviceId, currentPlayback)
                loading.visibility = View.GONE
                if (result.successful) {
                    // Always pause local playback after a successful transfer
                    // regardless of who was "active" before — only one device
                    // should emit audio at a time.
                    if (device.deviceId != repository.localDeviceId) {
                        requireContext().startService(
                            Intent(
                                requireContext(),
                                MusicPlaybackService::class.java
                            ).apply {
                                action = MusicPlaybackService.ACTION_PAUSE
                            }
                        )
                    }
                    requireContext().startService(
                        Intent(
                            requireContext(),
                            MusicPlaybackService::class.java
                        ).apply {
                            action = MusicPlaybackService.ACTION_CONNECT_SYNC
                        }
                    )
                }
                showResonantSnackbar(
                    text = result.message,
                    colorRes = if (result.successful) {
                        R.color.successColor
                    } else {
                        R.color.errorColor
                    },
                    iconRes = if (result.successful) {
                        R.drawable.ic_success
                    } else {
                        R.drawable.ic_error
                    }
                )
                if (result.successful) dismiss()
            }
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.uiState.collect { state ->
                    // Always show all devices, including this device so the user
                    // can see the full picture. Only exclude the current active
                    // device from being tappable (handled in adapter).
                    adapter.submitList(state.devices)
                    loading.visibility =
                        if (state.refreshing) View.VISIBLE else View.GONE
                    error.text = when {
                        state.refreshing -> null
                        // Don't block the picker when support is unconfirmed —
                        // the backend may simply not have responded yet. Show a
                        // softer warning instead of a hard block.
                        !state.supported && state.devices.isEmpty() ->
                            "Buscando dispositivos disponibles…"
                        state.devices.size <= 1 ->
                            state.errorMessage
                                ?: "Abre Resonant en otro dispositivo para verlo aquí."
                        else -> state.errorMessage
                    }
                    error.visibility = if (error.text.isNullOrBlank()) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }
            }
        }

        // Device detection must not depend on MusicPlaybackService already
        // being alive and mid-heartbeat: announce this device's presence the
        // moment the picker opens, then keep polling the device list while
        // it's visible so other devices that just came online show up without
        // the user having to close and reopen the sheet.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.announcePresenceAsync()
                while (true) {
                    repository.refreshAsync()
                    delay(DEVICE_LIST_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private companion object {
        private const val DEVICE_LIST_POLL_INTERVAL_MS = 4_000L
    }

    /**
     * Builds the current playback state directly from PlaybackStateRepository.
     * This is sent with the transfer request so the backend can relay the
     * EXACT current song and position to the target device without depending
     * on stale heartbeat data.
     */
    private fun buildTransferPlaybackState(): PlaybackConnectStateDTO? {
        val queue = PlaybackStateRepository.activeQueue ?: return null
        if (queue.songs.isEmpty()) return null
        val currentIndex = queue.currentIndex.coerceIn(queue.songs.indices)
        val queueItems = queue.songs.map { song ->
            PlaybackConnectQueueItemDTO(
                songId = song.id,
                title = song.title,
                artistName = song.artistName
                    ?: song.artists.joinToString(", ") { it.name }
                        .takeIf(String::isNotBlank),
                coverUrl = song.coverUrl,
                durationMs = song.audioAnalysis?.durationMs?.toLong()
            )
        }
        return PlaybackConnectStateDTO(
            stateRevision = 0L,
            queueRevision = 0L,
            queueItems = queueItems,
            queueTruncated = false,
            currentIndex = currentIndex,
            positionMs = PlaybackStateRepository.playbackPositionLiveData.value
                ?.position?.coerceAtLeast(0L) ?: 0L,
            durationMs = PlaybackStateRepository.playbackPositionLiveData.value
                ?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L,
            isPlaying = PlaybackStateRepository.isPlaying,
            sourceType = queue.sourceType.name,
            sourceId = queue.sourceId.takeIf(String::isNotBlank),
            repeatMode = PlaybackStateRepository.repeatModeLiveData.value
                ?: PlaybackStateRepository.REPEAT_MODE_OFF,
            shuffleEnabled = PlaybackStateRepository.isShuffleEnabledLiveData.value == true
        )
    }
}
