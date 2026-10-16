package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.example.resonant.R
import com.example.resonant.data.network.NotificationItemDTO
import com.example.resonant.notifications.NotificationDeepLink
import com.example.resonant.ui.activities.MainActivity
import com.example.resonant.ui.adapters.NotificationAdapter
import com.example.resonant.ui.viewmodels.NotificationInboxEvent
import com.example.resonant.ui.viewmodels.NotificationInboxState
import com.example.resonant.ui.viewmodels.NotificationInboxViewModel
import kotlinx.coroutines.launch

/**
 * Inbox opened from the bell in Home. Tapping an item marks it as read and follows its
 * `deepLink` through `MainActivity.openNotificationTarget`; unknown links stay here.
 */
class NotificationInboxFragment : Fragment() {

    private lateinit var viewModel: NotificationInboxViewModel
    private lateinit var adapter: NotificationAdapter

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var error: View
    private lateinit var progress: View
    private lateinit var markAll: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_notification_inbox, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[NotificationInboxViewModel::class.java]

        list = view.findViewById(R.id.notificationInboxList)
        empty = view.findViewById(R.id.notificationInboxEmpty)
        error = view.findViewById(R.id.notificationInboxError)
        progress = view.findViewById(R.id.notificationInboxProgress)
        markAll = view.findViewById(R.id.notificationInboxMarkAll)

        view.findViewById<View>(R.id.notificationInboxBack).setOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.notificationInboxRetry).setOnClickListener { viewModel.loadFirstPage() }
        markAll.setOnClickListener { viewModel.markAllRead() }

        adapter = NotificationAdapter(onClick = ::onNotificationClick)
        val layoutManager = LinearLayoutManager(requireContext())
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - LOAD_MORE_THRESHOLD) viewModel.loadMore()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            NotificationInboxEvent.Close -> closeIfShown()
                        }
                    }
                }
            }
        }

        viewModel.loadFirstPageIfNeeded()
    }

    private fun render(state: NotificationInboxState) {
        adapter.submitList(state.items) {
            // A short first page cannot be scrolled: ask for the next one right away.
            if (state.loaded && !state.endReached && !state.loadingMore && !state.loadMoreFailed &&
                list.isAttachedToWindow && !list.canScrollVertically(1)
            ) {
                viewModel.loadMore()
            }
        }
        progress.isVisible = state.loading && state.items.isEmpty()
        error.isVisible = state.error && state.items.isEmpty()
        empty.isVisible = state.isEmpty
        markAll.isVisible = state.hasUnread
    }

    private fun onNotificationClick(item: NotificationItemDTO) {
        viewModel.markOpened(item)
        val target = NotificationDeepLink.parse(item.deepLink)
        // Not handled (null, unknown or unavailable destination): stay in the inbox.
        (activity as? MainActivity)?.openNotificationTarget(target)
    }

    private fun closeIfShown() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.notificationInboxFragment) navController.popBackStack()
    }

    private companion object {
        const val LOAD_MORE_THRESHOLD = 5
    }
}
