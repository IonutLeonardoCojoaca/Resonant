package com.example.resonant.ui.components

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R

/**
 * Controla `view_chart_period_selector`: control segmentado de 2 a 4 opciones con un
 * indicador del color de acento que se desliza bajo la opción elegida.
 * [onSelected] solo se llama cuando el usuario cambia a una opción distinta.
 */
class ChartPeriodSelector(
    root: View,
    @ColorInt accent: Int,
    labels: List<String>,
    initialIndex: Int,
    private val onSelected: (Int) -> Unit
) {

    private val indicator: View = root.findViewById(R.id.periodIndicator)
    private val options: List<TextView> = listOf(
        R.id.periodOption0, R.id.periodOption1, R.id.periodOption2, R.id.periodOption3
    ).map { root.findViewById(it) }

    private val selectedTextColor = Color.WHITE
    private val idleTextColor = ContextCompat.getColor(root.context, R.color.textTheme)

    var selectedIndex: Int = initialIndex
        private set

    init {
        val radius = 18 * root.resources.displayMetrics.density
        indicator.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                ColorUtils.blendARGB(accent, Color.BLACK, 0.45f),
                ColorUtils.blendARGB(accent, Color.BLACK, 0.12f)
            )
        ).apply { cornerRadius = radius }

        options.forEachIndexed { index, option ->
            // Admite de 2 a 4 opciones: las que sobran se ocultan y el resto se reparte el ancho.
            if (index >= labels.size) {
                option.visibility = View.GONE
                return@forEachIndexed
            }
            option.text = labels[index]
            option.setOnClickListener {
                if (index != selectedIndex) {
                    select(index, animate = true)
                    onSelected(index)
                }
            }
        }

        // El ancho de cada opción solo se conoce tras el layout: el indicador lo copia.
        options[0].addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (indicator.layoutParams.width != v.width) {
                indicator.layoutParams = indicator.layoutParams.apply { width = v.width }
                indicator.translationX = selectedIndex * v.width.toFloat()
            }
        }
        select(initialIndex, animate = false)
    }

    fun select(index: Int, animate: Boolean) {
        selectedIndex = index
        options.forEachIndexed { i, option ->
            option.setTextColor(if (i == index) selectedTextColor else idleTextColor)
        }
        val targetX = index * options[0].width.toFloat()
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            indicator.animate().translationX(targetX)
                .setDuration(380).setInterpolator(OvershootInterpolator(1.1f)).start()
        } else {
            indicator.translationX = targetX
        }
    }
}
