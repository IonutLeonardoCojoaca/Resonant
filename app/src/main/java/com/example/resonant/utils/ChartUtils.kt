package com.example.resonant.utils

import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import com.example.resonant.R

object ChartUtils {
    fun bindPositionChange(badge: TextView, icon: ImageView, positionChange: Int?) {
        when {
            positionChange == null || positionChange == 0 -> {
                badge.text = "—"
                badge.setTextColor(Color.GRAY)
                icon.setImageResource(R.drawable.ic_trend_neutral)
                icon.setColorFilter(Color.GRAY)
            }
            positionChange > 0 -> {
                badge.text = "+$positionChange"
                badge.setTextColor(Color.parseColor("#4CAF50"))
                icon.setImageResource(R.drawable.ic_trend_up)
                icon.setColorFilter(Color.parseColor("#4CAF50"))
            }
            else -> {
                badge.text = "$positionChange"
                badge.setTextColor(Color.parseColor("#F44336"))
                icon.setImageResource(R.drawable.ic_trend_down)
                icon.setColorFilter(Color.parseColor("#F44336"))
            }
        }
    }
}
