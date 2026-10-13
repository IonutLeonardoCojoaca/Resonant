package com.example.resonant.ui.components

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.example.resonant.R

/**
 * Pestañas con una píldora de color que se desliza bajo la activa (sustituye a las
 * filas de chips). Cada pestaña tiene su acento; con [setScrollProgress] la píldora
 * sigue al dedo mientras se arrastra un ViewPager2 (posición, ancho y color
 * interpolados), y [select] la lleva a su sitio con animación.
 */
class SlidingPillTabs @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private val density = resources.displayMetrics.density
    private val content = FrameLayout(context)
    private val indicator = View(context)
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabs = mutableListOf<TextView>()
    private var accents: List<Int> = emptyList()
    private var onSelected: ((Int) -> Unit)? = null
    private val argb = ArgbEvaluator()
    private val idleText = ContextCompat.getColor(context, R.color.textTheme)
    private val pill = GradientDrawable().apply { cornerRadius = 20 * density }

    var selectedIndex = 0
        private set

    /**
     * Con un ViewPager2 conectado es él quien mueve la píldora (vía [setScrollProgress]):
     * al tocar una pestaña solo se avisa, sin animación propia que compita con el pager.
     */
    var followsPager = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        val padding = (12 * density).toInt()
        setPadding(padding, 0, padding, 0)
        indicator.background = pill
        content.addView(indicator, FrameLayout.LayoutParams(0, (40 * density).toInt(), Gravity.CENTER_VERTICAL))
        content.addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (40 * density).toInt()))
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTabs(labels: List<String>, @ColorInt tabAccents: List<Int>, initialIndex: Int, onTabSelected: (Int) -> Unit) {
        accents = tabAccents
        onSelected = onTabSelected
        row.removeAllViews()
        tabs.clear()
        val font = ResourcesCompat.getFont(context, R.font.unageo_semi_bold)
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        labels.forEachIndexed { index, label ->
            val tab = TextView(context).apply {
                text = label
                typeface = font
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    if (index == selectedIndex) return@setOnClickListener
                    if (followsPager) markSelected(index) else select(index, animate = true)
                    onSelected?.invoke(index)
                }
            }
            row.addView(tab, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            tabs += tab
        }
        selectedIndex = initialIndex
        // Las medidas de cada pestaña solo existen tras el layout.
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeIndicator(selectedIndex.toFloat()) }
        updateTextColors(initialIndex.toFloat())
    }

    /** Selecciona [index]; con [animate] la píldora viaja hasta él. */
    fun select(index: Int, animate: Boolean) {
        val from = selectedIndex
        selectedIndex = index
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || tabs.isEmpty()) {
            placeIndicator(index.toFloat())
            updateTextColors(index.toFloat())
        } else {
            ValueAnimator.ofFloat(from.toFloat(), index.toFloat()).apply {
                duration = 320
                interpolator = ResonantMotion.expoOut
                addUpdateListener {
                    val p = it.animatedValue as Float
                    placeIndicator(p)
                    updateTextColors(p)
                }
                start()
            }
        }
        scrollToTab(index)
    }

    /** Marca la pestaña activa sin animar la píldora (la mueve el pager). */
    fun markSelected(index: Int) {
        selectedIndex = index
        scrollToTab(index)
    }

    /** Posición continua mientras se arrastra el ViewPager2 (p. ej. 1.4 = entre la 2ª y la 3ª). */
    fun setScrollProgress(position: Int, offset: Float) {
        val p = position + offset
        placeIndicator(p)
        updateTextColors(p)
    }

    private fun placeIndicator(position: Float) {
        if (tabs.isEmpty()) return
        val i = position.toInt().coerceIn(0, tabs.lastIndex)
        val next = (i + 1).coerceAtMost(tabs.lastIndex)
        val f = (position - i).coerceIn(0f, 1f)
        val a = tabs[i]
        val b = tabs[next]
        if (a.width == 0) return
        val left = a.left + (b.left - a.left) * f
        val width = a.width + (b.width - a.width) * f
        indicator.translationX = left
        val params = indicator.layoutParams
        if (params.width != width.toInt()) {
            params.width = width.toInt()
            indicator.layoutParams = params
        }
        val color = argb.evaluate(f, accents.getOrElse(i) { Color.GRAY }, accents.getOrElse(next) { Color.GRAY }) as Int
        pill.setColor(ColorUtils.blendARGB(color, Color.BLACK, 0.3f))
    }

    private fun updateTextColors(position: Float) {
        tabs.forEachIndexed { index, tab ->
            val closeness = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
            tab.setTextColor(argb.evaluate(closeness, idleText, Color.WHITE) as Int)
        }
    }

    private fun scrollToTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        post { smoothScrollTo((tab.left + tab.width / 2 - width / 2).coerceAtLeast(0), 0) }
    }
}
