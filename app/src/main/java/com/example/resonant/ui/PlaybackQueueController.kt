package com.example.resonant.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.SessionResult
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueCommands
import com.example.resonant.playback.QueueSnapshot
import com.example.resonant.playback.QueueSource
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
    private val relatedRecycler: RecyclerView?
) {
    private lateinit var adapter: PlaybackQueueAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var relatedAdapter: SongAdapter

    private var snapshot: QueueSnapshot? = null
    private var visibleQueueStart = 0
    private var dragFromQueueIndex: Int? = null
    private var dragToQueueIndex: Int? = null
    private var relatedSeedSongId: String? = null
    private val swipeBackground = ColorDrawable(Color.parseColor("#B3261E"))
    private val swipeDeleteIcon by lazy {
        ContextCompat.getDrawable(fragment.requireContext(), R.drawable.ic_cancel)
    }
    private val songManager by lazy { SongManager(fragment.requireContext()) }

    /** Wires the RecyclerView/adapter/touch-helper and starts observing the live queue. */
    fun start() {
        adapter = PlaybackQueueAdapter(
            onPlay = { item ->
                sendIndexCommand(QueueCommands.PLAY_INDEX, item.queueIndex)
            },
            onRemove = { item ->
                sendIndexCommand(QueueCommands.REMOVE, item.queueIndex)
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
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
            subtitle.text = ""
            adapter.submitQueue(emptyList())
            return
        }

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
            val related = songManager.getRelatedSongs(currentSong.id, limit = 10)
            if (!fragment.isAdded || relatedSeedSongId != currentSong.id) return@launch
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
}
