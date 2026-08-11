package com.example.resonant.ui.bottomsheets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.media3.session.SessionResult
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueCommands
import com.example.resonant.playback.QueueSnapshot
import com.example.resonant.playback.QueueSource
import com.example.resonant.ui.adapters.PlaybackQueueAdapter
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

class PlaybackQueueBottomSheet : ResonantBottomSheetDialogFragment() {
    private lateinit var adapter: PlaybackQueueAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var recycler: RecyclerView
    private lateinit var subtitle: TextView
    private lateinit var hint: TextView
    private lateinit var clear: TextView
    private lateinit var shuffle: TextView
    private lateinit var actions: View
    private lateinit var empty: TextView

    private var snapshot: QueueSnapshot? = null
    private var visibleQueueStart = 0
    private var dragFromQueueIndex: Int? = null
    private var dragToQueueIndex: Int? = null
    private val swipeBackground = ColorDrawable(Color.parseColor("#B3261E"))
    private val swipeDeleteIcon by lazy {
        ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_playback_queue, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.playback_queue_list)
        subtitle = view.findViewById(R.id.queue_subtitle)
        hint = view.findViewById(R.id.queue_reorder_hint)
        clear = view.findViewById(R.id.clear_upcoming)
        shuffle = view.findViewById(R.id.shuffle_upcoming)
        actions = view.findViewById(R.id.queue_actions)
        empty = view.findViewById(R.id.empty_queue)

        adapter = PlaybackQueueAdapter(
            onPlay = { item ->
                sendIndexCommand(QueueCommands.PLAY_INDEX, item.queueIndex)
            },
            onRemove = { item ->
                sendIndexCommand(QueueCommands.REMOVE, item.queueIndex)
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
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

        PlaybackStateRepository.queueSnapshotLiveData.observe(viewLifecycleOwner) {
            render(it)
        }
    }

    private fun render(value: QueueSnapshot?) {
        snapshot = value
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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                PlaybackControllerConnection.sendQueueCommand(
                    requireContext(),
                    action,
                    args
                )
            }.getOrElse { error ->
                if (rollbackOnFailure) snapshot?.let(::render)
                showResonantSnackbar(
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
                    showResonantSnackbar(message, R.color.successColor, R.drawable.ic_success)
                }
            } else {
                if (rollbackOnFailure) snapshot?.let(::render)
                showResonantSnackbar(
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
                    val iconSize = (22 * resources.displayMetrics.density).toInt()
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
