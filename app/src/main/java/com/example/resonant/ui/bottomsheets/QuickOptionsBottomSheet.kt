package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Hoja de opciones para lo que no tiene una propia (géneros, listas públicas, mixes de
 * artista…). Misma cabecera y filas que el resto; las opciones llegan como lista.
 */
class QuickOptionsBottomSheet(
    private val title: String,
    private val subtitle: String?,
    private val imageUrl: String?,
    @DrawableRes private val placeholder: Int,
    private val options: List<Option>,
    private val circularImage: Boolean = false
) : ResonantBottomSheetDialogFragment() {

    override val hasOptionRows = true

    data class Option(
        @DrawableRes val icon: Int,
        val label: String,
        val tone: OptionRows.Tone = OptionRows.Tone.NORMAL,
        val action: () -> Unit
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_quick_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.quickTitle).text = title
        view.findViewById<TextView>(R.id.quickSubtitle).apply {
            text = subtitle
            isVisible = !subtitle.isNullOrBlank()
        }
        val image = view.findViewById<ShapeableImageView>(R.id.quickImage)
        if (circularImage) {
            image.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(ShapeAppearanceModel.PILL).build()
        }
        Glide.with(this).load(imageUrl).placeholder(placeholder).error(placeholder).centerCrop().into(image)

        val rows = view.findViewById<LinearLayout>(R.id.quickRows)
        options.forEach { option ->
            val row = TextView(requireContext(), null, 0, styleFor(option.tone)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = option.label
                setCompoundDrawablesRelativeWithIntrinsicBounds(option.icon, 0, 0, 0)
                setOnClickListener {
                    dismiss()
                    option.action()
                }
            }
            OptionRows.setTone(row, option.tone)
            rows.addView(row)
        }
        // La base normaliza los iconos a 24dp con las filas ya creadas.
        super.onViewCreated(view, savedInstanceState)
    }

    private fun styleFor(tone: OptionRows.Tone) = when (tone) {
        OptionRows.Tone.DESTRUCTIVE, OptionRows.Tone.ACTIVE -> R.style.OptionRow_Destructive
        OptionRows.Tone.MUTED -> R.style.OptionRow_Muted
        OptionRows.Tone.NORMAL -> R.style.OptionRow
    }
}
