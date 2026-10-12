package com.example.resonant.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.example.resonant.R

/**
 * "✦ Recomendado por Aria" badge. It marks what Aria picked (Explore, and later Live and the song
 * of the day) and never regular recommendations, so it must look the same wherever it is used.
 */
class AriaBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_explore_badge)
        val horizontal = (8 * resources.displayMetrics.density).toInt()
        val vertical = (4 * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        LayoutInflater.from(context).inflate(R.layout.view_aria_badge, this, true)
    }
}
