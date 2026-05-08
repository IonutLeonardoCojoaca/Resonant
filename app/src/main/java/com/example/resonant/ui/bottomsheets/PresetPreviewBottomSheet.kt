package com.example.resonant.ui.bottomsheets

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.resonant.R
import com.example.resonant.data.network.TransitionPresetPreviewDTO
import com.example.resonant.databinding.BottomSheetPresetPreviewBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PresetPreviewBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPresetPreviewBinding? = null
    private val binding get() = _binding!!

    private var preview: TransitionPresetPreviewDTO? = null
    private var presetDescription: String? = null
    private var onApply: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetPresetPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val p = preview ?: run { dismiss(); return }

        showContent(p)

        binding.previewCancelButton.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }

        binding.previewApplyButton.setOnClickListener {
            onApply?.invoke(p.presetCode)
            dismiss()
        }
    }

    private fun showContent(preview: TransitionPresetPreviewDTO) {
        binding.previewLoading.visibility = View.GONE
        binding.previewContent.visibility = View.VISIBLE

        val iconEmoji = getPresetIcon(preview.presetCode)
        binding.previewPresetName.text = "$iconEmoji ${preview.presetName}"
        binding.previewPresetDescription.text = presetDescription ?: ""
        binding.previewPresetDescription.visibility =
            if (presetDescription.isNullOrBlank()) View.GONE else View.VISIBLE

        val cv = preview.calculatedValues
        binding.previewCrossfade.text = formatMsReadable(cv.crossfadeDurationMs)
        binding.previewCurve.text = cv.fadeCurveType.replaceFirstChar { it.uppercase() }
        binding.previewExitPoint.text = formatMs(cv.exitPointMs)
        binding.previewEntryPoint.text = formatMs(cv.entryPointMs)

        if (cv.gapMs > 0) {
            binding.previewGapRow.visibility = View.VISIBLE
            binding.previewGap.text = "${cv.gapMs}ms"
        } else {
            binding.previewGapRow.visibility = View.GONE
        }

        // Warnings
        if (preview.warnings.isNotEmpty()) {
            binding.previewWarningsContainer.visibility = View.VISIBLE
            binding.previewWarningsContainer.removeAllViews()
            preview.warnings.forEach { warning ->
                val tv = TextView(requireContext()).apply {
                    text = "⚠️ $warning"
                    setTextColor(Color.parseColor("#FFC107"))
                    textSize = 12f
                    typeface = resources.getFont(R.font.unageo_medium)
                    setBackgroundResource(R.drawable.bg_warning_banner)
                    setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.bottomMargin = 4.dp
                    layoutParams = params
                }
                binding.previewWarningsContainer.addView(tv)
            }
        } else {
            binding.previewWarningsContainer.visibility = View.GONE
        }

        // Set up mini waveform preview with calculated points
        binding.previewWaveform.fadeCurveType = cv.fadeCurveType
        binding.previewWaveform.setTransitionPoints(
            cv.exitPointMs, cv.entryPointMs, cv.crossfadeDurationMs
        )
    }

    private fun formatMs(ms: Int): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val decimal = (ms % 1000) / 100
        return "$minutes:${seconds.toString().padStart(2, '0')}.$decimal"
    }

    private fun formatMsReadable(ms: Int): String {
        return if (ms >= 1000) {
            val seconds = ms / 1000.0
            String.format("%.1fs", seconds)
        } else {
            "${ms}ms"
        }
    }

    private fun getPresetIcon(code: String): String = when (code) {
        "smooth-fade" -> "🌊"
        "beat-match" -> "🎵"
        "hard-cut" -> "✂️"
        "energy-boost" -> "⚡"
        "vocal-blend" -> "🎤"
        else -> "🎛️"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            preview: TransitionPresetPreviewDTO,
            description: String?,
            onApply: (String) -> Unit,
            onCancel: () -> Unit
        ): PresetPreviewBottomSheet {
            return PresetPreviewBottomSheet().apply {
                this.preview = preview
                this.presetDescription = description
                this.onApply = onApply
                this.onCancel = onCancel
            }
        }
    }
}
