package com.example.resonant.utils

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.resonant.R

object ChartUtils {
    fun bindPositionChange(badge: TextView, icon: ImageView, positionChange: Int?) {
        when {
            positionChange == null || positionChange == 0 -> {
                badge.text = ""
                icon.visibility = View.GONE
                badge.visibility = View.GONE
            }
            positionChange > 0 -> {
                badge.visibility = View.VISIBLE
                icon.visibility = View.VISIBLE
                badge.text = "+$positionChange"
                badge.setTextColor(Color.parseColor("#4CAF50"))
                icon.setImageResource(R.drawable.ic_trend_up)
                icon.setColorFilter(Color.parseColor("#4CAF50"))
            }
            else -> {
                badge.visibility = View.VISIBLE
                icon.visibility = View.VISIBLE
                badge.text = "$positionChange"
                badge.setTextColor(Color.parseColor("#F44336"))
                icon.setImageResource(R.drawable.ic_trend_down)
                icon.setColorFilter(Color.parseColor("#F44336"))
            }
        }
    }
}
