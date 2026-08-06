package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.managers.AudioQuality
import com.example.resonant.managers.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AudioQualityBottomSheet : ResonantBottomSheetDialogFragment() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_audio_quality, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())

        val streamingDropdown = view.findViewById<AutoCompleteTextView>(R.id.streaming_quality_dropdown)
        val downloadDropdown  = view.findViewById<AutoCompleteTextView>(R.id.download_quality_dropdown)

        val streamingOptions = AudioQuality.entries.map { it.displayName }
        val downloadOptions  = AudioQuality.entries
            .filter { it != AudioQuality.AUTO }
            .map { it.displayName }

        val streamingAdapter = makeAdapter(streamingOptions)
        val downloadAdapter  = makeAdapter(downloadOptions)

        streamingDropdown.setAdapter(streamingAdapter)
        downloadDropdown.setAdapter(downloadAdapter)

        // Populate current selections
        viewLifecycleOwner.lifecycleScope.launch {
            val currentStreaming = settingsManager.streamingQualityFlow.first()
            val currentDownload  = settingsManager.downloadQualityFlow.first()
            streamingDropdown.setText(currentStreaming.displayName, false)
            downloadDropdown.setText(currentDownload.displayName, false)

            // Wire listeners after initial values are set to avoid spurious saves
            streamingDropdown.setOnItemClickListener { _, _, position, _ ->
                val selected = AudioQuality.entries[position]
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setStreamingQuality(selected)
                }
            }

            // Download list excludes AUTO — map position back to the filtered list
            val downloadEntries = AudioQuality.entries.filter { it != AudioQuality.AUTO }
            downloadDropdown.setOnItemClickListener { _, _, position, _ ->
                val selected = downloadEntries[position]
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setDownloadQuality(selected)
                }
            }
        }
    }

    private fun makeAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), R.layout.item_quality_dropdown, items).also {
            it.setDropDownViewResource(R.layout.item_quality_dropdown)
        }
}
