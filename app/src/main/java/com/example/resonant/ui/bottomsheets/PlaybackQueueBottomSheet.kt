package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.ui.PlaybackQueueController

class PlaybackQueueBottomSheet : ResonantBottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_playback_queue, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        PlaybackQueueController(
            fragment = this,
            recycler = view.findViewById<RecyclerView>(R.id.playback_queue_list),
            subtitle = view.findViewById<TextView>(R.id.queue_subtitle),
            hint = view.findViewById<TextView>(R.id.queue_reorder_hint),
            clear = view.findViewById<TextView>(R.id.clear_upcoming),
            shuffle = view.findViewById<TextView>(R.id.shuffle_upcoming),
            actions = view.findViewById<View>(R.id.queue_actions),
            empty = view.findViewById<TextView>(R.id.empty_queue),
            relatedSection = view.findViewById<View>(R.id.queueRelatedSection),
            relatedRecycler = view.findViewById<RecyclerView>(R.id.queueRelatedList)
        ).start()
    }
}
