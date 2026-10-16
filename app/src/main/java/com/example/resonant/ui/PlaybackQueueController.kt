package com.example.resonant.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.SessionResult
import androidx.navigation.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.FlowSavePlaylistRequestDTO
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.FlowPreferencesManager
import com.example.resonant.managers.SettingsManager
import com.example.resonant.playback.flow.FlowQueuePolicy
import com.example.resonant.ui.bottomsheets.FlowSongOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.SongOptionsBottomSheet
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueCommands
import com.example.resonant.playback.QueueSnapshot
import com.example.resonant.playback.QueueSource
import com.example.resonant.playback.PlaySourceMapper
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.PlaybackQueueAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

/**
 * Owns the "Up Next" queue UI: drag-reorder, swipe-to-delete, shuffle/clear
 * actions and command-sending with rollback-on-failure. Extracted out of
 * [com.example.resonant.ui.bottomsheets.PlaybackQueueBottomSheet] so the same
 * behavior can be reused from SongFragment's inline tab without duplicating
 * the ItemTouchHelper.Callback (drag/swipe drawing + command logic is
 * non-trivial and was previously only wired up in one place).
 *
 * The caller owns the layout and passes in already-found views; this class
 * only wires behavior, it never inflates anything itself.
 *
 * [relatedSection]/[relatedRecycler] are optional: pass null when the host
 * screen already has its own dedicated "related songs" surface elsewhere
 * (e.g. SongFragment's own Related tab) and doesn't need it duplicated
 * inside the queue.
 */
class PlaybackQueueController(
    private val fragment: Fragment,
    private val recycler: RecyclerView,
    private val subtitle: TextView,
    private val hint: TextView,
    private val clear: TextView,
    private val shuffle: TextView,
    private val actions: View,
    private val empty: TextView,
    private val relatedSection: View?,
    private val relatedRecycler: RecyclerView?,
    /** Bloque `view_flow_queue_controls` incluido en el layout del host. */
    private val flowControls: View? = null
) {
    private lateinit var adapter: PlaybackQueueAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var relatedAdapter: SongAdapter

    private var snapshot: QueueSnapshot? = null
    private var visibleQueueStart = 0
    private var dragFromQueueIndex: Int? = null
    private var dragToQueueIndex: Int? = null
    private var relatedSeedSongId: String? = null
    /** `requestId` de las relacionadas mostradas (impresiones `RELATED`). */
    private var relatedRequestId: String? = null
    private var flowV2Enabled = false
    private var saveSessionButton: View? = null
    private val swipeBackground = ColorDrawable(Color.parseColor("#B3261E"))
    private val swipeDeleteIcon by lazy {
        ContextCompat.getDrawable(fragment.requireContext(), R.drawable.ic_cancel)
    }
    private val songManager by lazy { SongManager(fragment.requireContext()) }
    private val settingsManager by lazy { SettingsManager(fragment.requireContext()) }

    /** Wires the RecyclerView/adapter/touch-helper and starts observing the live queue. */
    fun start() {
        adapter = PlaybackQueueAdapter(
            onPlay = { item ->
                sendIndexCommand(QueueCommands.PLAY_INDEX, item.queueIndex)
            },
            onRemove = { item ->
                sendIndexCommand(QueueCommands.REMOVE, item.queueIndex)
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            onFlowLongPress = { item ->
                if (CapabilitiesManager.isFlowV2Enabled()) showFlowOptions(item.song)
            }
        )
        recycler.layoutManager = LinearLayoutManager(fragment.requireContext())
        recycler.adapter = adapter
        touchHelper = ItemTouchHelper(queueTouchCallback)
        touchHelper.attachToRecyclerView(recycler)

        clear.setOnClickListener {
            val current = snapshot ?: return@setOnClickListener
            sendCommand(
                QueueCommands.CLEAR_UPCOMING,
                Bundle().apply {
                    putLong(QueueCommands.ARG_EXPECTED_REVISION, current.revision)
                }
            )
        }

        shuffle.setOnClickListener {
            val current = snapshot ?: return@setOnClickListener
            sendCommand(
                QueueCommands.SHUFFLE_UPCOMING,
                Bundle().apply {
                    putLong(QueueCommands.ARG_EXPECTED_REVISION, current.revision)
                }
            )
        }

        if (relatedRecycler != null) {
            relatedAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_GRID)
            relatedAdapter.itemWidthOverride = (150 * fragment.resources.displayMetrics.density).toInt()
            relatedRecycler.layoutManager = LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.HORIZONTAL, false)
            relatedRecycler.adapter = relatedAdapter
            relatedAdapter.onItemClick = { (song, _) -> playRelatedSong(song) }
        }

        PlaybackStateRepository.queueSnapshotLiveData.observe(fragment.viewLifecycleOwner) {
            render(it)
        }
        flowControls?.let(::bindFlowControls)
    }

    private fun showFlowOptions(song: Song) {
        FlowSongOptionsBottomSheet(song) { selected ->
            SongOptionsBottomSheet(selected).show(fragment.parentFragmentManager, "SongOptionsBottomSheet")
        }.show(fragment.parentFragmentManager, "FlowSongOptionsBottomSheet")
    }

    /**
     * "Continuar reproduciendo" (el mismo ajuste persistente que el de Ajustes) y, con la
     * capacidad `flowV2`, el nivel de descubrimiento y "Guardar esta sesión".
     */
    private fun bindFlowControls(controls: View) {
        val continueSwitch = controls.findViewById<CompoundButton>(R.id.flow_continue_switch)
        val owner = fragment.viewLifecycleOwner
        var applyingSetting = false
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.autoplayRadioEnabledFlow.collect { enabled ->
                    if (continueSwitch.isChecked != enabled) {
                        applyingSetting = true
                        continueSwitch.isChecked = enabled
                        applyingSetting = false
                    }
                }
            }
        }
        // Toda la fila actúa como el interruptor.
        controls.findViewById<View>(R.id.flow_continue_row).setOnClickListener { continueSwitch.toggle() }

        val context = fragment.requireContext()
        val accent = ResonantAccent.TRENDING.color // acento de Flow (Explorar, novedades)
        controls.findViewById<View>(R.id.flow_badge).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent, 0x2E))
        }
        controls.findViewById<ImageView>(R.id.flow_badge_icon).imageTintList = ColorStateList.valueOf(accent)
        controls.findViewById<TextView>(R.id.flow_eyebrow).setTextColor(accent)

        val discoveryGroup = controls.findViewById<View>(R.id.flow_discovery_group)
        val discoveryHint = controls.findViewById<TextView>(R.id.flow_discovery_hint)
        val levelLabels = LEVEL_LABELS.map(context::getString)
        val discoverySelector = ChartPeriodSelector(
            controls.findViewById(R.id.flow_discovery_selector),
            accent,
            levelLabels,
            initialIndex = DEFAULT_LEVEL
        ) { level ->
            discoveryHint.setText(LEVEL_HINTS[level])
            owner.lifecycleScope.launch { FlowPreferencesManager.setDiscoveryLevel(fragment.requireContext(), level) }
        }
        discoveryHint.setText(LEVEL_HINTS[DEFAULT_LEVEL])

        // Con Flow apagado el nivel no aplica: se atenúa pero sigue visible.
        fun applyEnabled(enabled: Boolean) {
            discoveryGroup.animate().alpha(if (enabled) 1f else 0.4f).setDuration(180).start()
        }
        applyEnabled(continueSwitch.isChecked)
        continueSwitch.setOnCheckedChangeListener { _, isChecked ->
            applyEnabled(isChecked)
            if (applyingSetting) return@setOnCheckedChangeListener
            owner.lifecycleScope.launch { FlowPreferencesManager.setEnabled(fragment.requireContext(), isChecked) }
        }

        val saveSession = controls.findViewById<View>(R.id.flow_save_session)
        owner.lifecycleScope.launch {
            CapabilitiesManager.refresh(fragment.requireContext())
            flowV2Enabled = CapabilitiesManager.isFlowV2Enabled()
            discoveryGroup.visibility = if (flowV2Enabled) View.VISIBLE else View.GONE
            snapshot?.let(::render)
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.flowDiscoveryLevelFlow.collect { stored ->
                    val level = stored.takeIf { it in LEVEL_LABELS.indices } ?: DEFAULT_LEVEL
                    if (discoverySelector.selectedIndex != level) {
                        discoverySelector.select(level, animate = false)
                        discoveryHint.setText(LEVEL_HINTS[level])
                    }
                }
            }
        }
        saveSessionButton = saveSession
        saveSession.setOnClickListener { saveFlowSession(saveSession) }
    }

    private fun saveFlowSession(button: View) {
        val current = snapshot ?: return
        val songIds = FlowQueuePolicy.sessionSongIds(current.items.map { it.song }, current.currentIndex)
        if (songIds.isEmpty()) return
        button.isEnabled = false
        val context = fragment.requireContext().applicationContext
        val activity = fragment.requireActivity()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val saved = runCatching {
                ApiClient.getRadioService(context)
                    .saveFlowSession(FlowSavePlaylistRequestDTO(songIds = songIds))
                    .takeIf { it.isSuccessful }?.body()
            }.getOrNull()
            button.isEnabled = true
            if (!fragment.isAdded) return@launch
            if (saved != null) {
                val playlistId = saved.playlistId?.takeIf(String::isNotBlank)
                fragment.showResonantSnackbar(
                    text = fragment.getString(R.string.flow_save_session_done, saved.name.orEmpty()),
                    colorRes = R.color.successColor,
                    iconRes = R.drawable.ic_success,
                    actionText = playlistId?.let { fragment.getString(R.string.flow_save_session_open) },
                    onAction = playlistId?.let { id -> { openSavedPlaylist(activity, id) } }
                )
            } else {
                fragment.showResonantSnackbar(
                    fragment.getString(R.string.flow_save_session_failed),
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
            }
        }
    }

    /**
     * Abre la playlist recién guardada. La cola vive en una hoja o en el reproductor a pantalla
     * completa (diálogos sobre la actividad): se cierran para que se vea la playlist.
     */
    private fun openSavedPlaylist(activity: FragmentActivity, playlistId: String) {
        runCatching {
            (fragment as? DialogFragment)?.takeIf { it.isAdded }?.dismissAllowingStateLoss()
            (activity.supportFragmentManager.findFragmentByTag(SONG_FRAGMENT_TAG) as? DialogFragment)
                ?.dismissAllowingStateLoss()
            activity.findNavController(R.id.nav_host_fragment)
                .navigate(R.id.action_global_to_playlistFragment, bundleOf("playlistId" to playlistId))
        }.onFailure { Log.w(TAG, "No se pudo abrir la playlist guardada", it) }
    }

    private fun render(value: QueueSnapshot?) {
        snapshot = value
        updateRelatedSection(value?.items?.firstOrNull { it.isCurrent }?.song)
        if (value == null || value.items.isEmpty()) {
            recycler.visibility = View.GONE
            clear.visibility = View.GONE
            actions.visibility = View.GONE
            hint.visibility = View.GONE
            empty.visibility = View.VISIBLE
            saveSessionButton?.visibility = View.GONE
            subtitle.text = ""
            adapter.submitQueue(emptyList())
            return
        }

        saveSessionButton?.visibility =
            if (flowV2Enabled && value.items.any { it.isFlow }) View.VISIBLE else View.GONE
        visibleQueueStart = value.currentIndex
        val visibleItems = value.items.drop(value.currentIndex)
        adapter.canReorderUpcoming = value.canReorderUpcoming
        adapter.submitQueue(visibleItems)
        recycler.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        clear.visibility = View.VISIBLE
        empty.visibility = View.GONE
        val pendingCount = (value.items.size - value.currentIndex - 1).coerceAtLeast(0)
        clear.isEnabled = pendingCount > 0
        clear.alpha = if (clear.isEnabled) 1f else 0.42f
        shuffle.isEnabled = pendingCount > 1 && value.sourceType != QueueSource.PLAYMIX
        shuffle.alpha = if (shuffle.isEnabled) 1f else 0.42f
        subtitle.text = if (pendingCount == 1) {
            "1 canción pendiente"
        } else {
            "$pendingCount canciones pendientes"
        }

        val guidance = when {
            value.sourceType == QueueSource.PLAYMIX ->
                "El orden de una PlayMix está vinculado a sus transiciones."
            value.shuffleEnabled ->
                "El modo aleatorio está activo. Pulsa Mezclar para crear un orden manual nuevo."
            pendingCount > 0 ->
                "Arrastra el asa para ordenar · desliza a la izquierda para eliminar"
            else -> "Añade canciones desde cualquier menú para construir tu cola"
        }
        hint.text = guidance
        hint.visibility = View.VISIBLE
    }

    /**
     * "Relacionadas" con la canción que suena ahora mismo — se recarga solo
     * cuando cambia el id de la canción actual (no en cada snapshot, p. ej.
     * al reordenar o mezclar la cola), y se guarda contra fragment.isAdded /
     * un cambio de canción a mitad de la petición de red.
     */
    private fun updateRelatedSection(currentSong: Song?) {
        val section = relatedSection ?: return
        if (currentSong == null) {
            relatedSeedSongId = null
            section.visibility = View.GONE
            return
        }
        if (relatedSeedSongId == currentSong.id) return
        relatedSeedSongId = currentSong.id

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = songManager.getRelatedSongsWithRequestId(currentSong.id, limit = 10)
            val related = result.songs
            if (!fragment.isAdded || relatedSeedSongId != currentSong.id) return@launch
            relatedRequestId = result.requestId
            if (related.isEmpty()) {
                section.visibility = View.GONE
            } else {
                relatedAdapter.submitList(related)
                section.visibility = View.VISIBLE
            }
        }
    }

    private fun playRelatedSong(song: Song) {
        val relatedSongs = ArrayList(relatedAdapter.currentList)
        val index = relatedSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val seedId = relatedSeedSongId ?: "RELATED_UNKNOWN"
        val playIntent = Intent(fragment.requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.Companion.ACTION_PLAY
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, song)
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, index)
            putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, relatedSongs)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.RELATED_SONGS)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, seedId)
            putExtra(MusicPlaybackService.Companion.EXTRA_PLAY_SOURCE_CANONICAL, PlaySourceMapper.RELATED)
            relatedRequestId?.let { putExtra(MusicPlaybackService.Companion.EXTRA_RECOMMENDATION_REQUEST_ID, it) }
        }
        fragment.requireContext().startService(playIntent)
    }

    private fun sendIndexCommand(action: String, queueIndex: Int) {
        val current = snapshot ?: return
        sendCommand(
            action,
            Bundle().apply {
                putInt(QueueCommands.ARG_QUEUE_INDEX, queueIndex)
                putLong(QueueCommands.ARG_EXPECTED_REVISION, current.revision)
            }
        )
    }

    private fun sendMoveCommand(from: Int, to: Int) {
        if (from == to) return
        val current = snapshot ?: return
        sendCommand(
            QueueCommands.MOVE,
            Bundle().apply {
                putInt(QueueCommands.ARG_FROM_INDEX, from)
                putInt(QueueCommands.ARG_TO_INDEX, to)
                putLong(QueueCommands.ARG_EXPECTED_REVISION, current.revision)
            },
            rollbackOnFailure = true
        )
    }

    private fun sendCommand(
        action: String,
        args: Bundle,
        rollbackOnFailure: Boolean = false
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                PlaybackControllerConnection.sendQueueCommand(
                    fragment.requireContext(),
                    action,
                    args
                )
            }.getOrElse { error ->
                if (rollbackOnFailure) snapshot?.let(::render)
                fragment.showResonantSnackbar(
                    error.localizedMessage ?: "No se pudo modificar la cola",
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
                return@launch
            }
            val message = result.extras
                .getString(QueueCommands.RESULT_MESSAGE)
                .orEmpty()
            if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                if (message.isNotBlank() && action != QueueCommands.PLAY_INDEX) {
                    fragment.showResonantSnackbar(message, R.color.successColor, R.drawable.ic_success)
                }
            } else {
                if (rollbackOnFailure) snapshot?.let(::render)
                fragment.showResonantSnackbar(
                    message.ifBlank { "La cola cambió; vuelve a intentarlo" },
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
            }
        }
    }

    private val queueTouchCallback = object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val item = adapter.itemAt(viewHolder.bindingAdapterPosition)
                ?: return makeMovementFlags(0, 0)
            if (item.isCurrent) return makeMovementFlags(0, 0)
            val dragFlags = if (adapter.canReorderUpcoming) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN
            } else {
                0
            }
            return makeMovementFlags(dragFlags, ItemTouchHelper.START)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            if (!adapter.canReorderUpcoming) return false
            val fromPosition = source.bindingAdapterPosition
            val toPosition = target.bindingAdapterPosition
            if (fromPosition <= 0 || toPosition <= 0) return false
            if (dragFromQueueIndex == null) {
                dragFromQueueIndex = visibleQueueStart + fromPosition
            }
            dragToQueueIndex = visibleQueueStart + toPosition
            return adapter.moveItem(fromPosition, toPosition)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val item = adapter.itemAt(viewHolder.bindingAdapterPosition)
            if (item != null && !item.isCurrent) {
                sendIndexCommand(QueueCommands.REMOVE, item.queueIndex)
            } else {
                snapshot?.let(::render)
            }
        }

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0f) {
                val itemView = viewHolder.itemView
                swipeBackground.setBounds(
                    itemView.right + dX.toInt(),
                    itemView.top,
                    itemView.right,
                    itemView.bottom
                )
                swipeBackground.draw(canvas)

                swipeDeleteIcon?.let { icon ->
                    val iconSize = (22 * fragment.resources.displayMetrics.density).toInt()
                    val margin = (itemView.height - iconSize) / 2
                    val right = itemView.right - margin
                    icon.setBounds(
                        right - iconSize,
                        itemView.top + margin,
                        right,
                        itemView.bottom - margin
                    )
                    icon.draw(canvas)
                }
            }
            super.onChildDraw(
                canvas,
                recyclerView,
                viewHolder,
                dX,
                dY,
                actionState,
                isCurrentlyActive
            )
        }

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)
            val from = dragFromQueueIndex
            val to = dragToQueueIndex
            dragFromQueueIndex = null
            dragToQueueIndex = null
            if (from != null && to != null) {
                sendMoveCommand(from, to)
            }
        }

        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = true
    }

    private companion object {
        const val TAG = "PlaybackQueue"
        /** Etiqueta con la que MainActivity muestra el reproductor a pantalla completa. */
        const val SONG_FRAGMENT_TAG = "SongFragment"
        /** Nivel de descubrimiento por índice: 0 familiar, 1 equilibrado, 2 descubrir. */
        val LEVEL_LABELS = listOf(R.string.flow_level_familiar, R.string.flow_level_balanced, R.string.flow_level_discover)
        val LEVEL_HINTS = listOf(
            R.string.flow_level_familiar_hint, R.string.flow_level_balanced_hint, R.string.flow_level_discover_hint
        )
        const val DEFAULT_LEVEL = 1
    }
}
