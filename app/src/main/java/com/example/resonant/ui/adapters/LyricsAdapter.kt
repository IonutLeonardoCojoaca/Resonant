package com.example.resonant.ui.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.managers.LyricLine
import kotlin.math.abs

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var lines: List<LyricLine> = emptyList()
    private var activeIndex: Int = -1

    companion object {
        private const val ALPHA_ACTIVE = 1.0f
        private const val ALPHA_NEARBY = 0.38f
        private const val ALPHA_FAR    = 0.14f
    }

    fun submitLines(newLines: List<LyricLine>) {
        lines = newLines
        activeIndex = -1
        notifyDataSetChanged()
    }

    fun updateActiveLine(newIndex: Int) {
        if (newIndex == activeIndex) return
        val old = activeIndex
        activeIndex = newIndex

        if (old == -1 || kotlin.math.abs(old - newIndex) > 5) {
            notifyDataSetChanged()
        } else {
            val minRefresh = (minOf(old, newIndex).coerceAtLeast(0) - 3).coerceAtLeast(0)
            val maxRefresh = (maxOf(old, newIndex).coerceAtMost(lines.size - 1) + 3).coerceAtMost(lines.size - 1)
            for (i in minRefresh..maxRefresh) {
                notifyItemChanged(i)
            }
        }
    }

    fun clearActiveLine() {
        if (activeIndex == -1) return
        val old = activeIndex
        activeIndex = -1
        val minRefresh = (old - 3).coerceAtLeast(0)
        val maxRefresh = (old + 3).coerceAtMost(lines.size - 1)
        for (i in minRefresh..maxRefresh) {
            notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position], position)
    }

    override fun getItemCount(): Int = lines.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.lyricText)
        private val mediumTypeface = ResourcesCompat.getFont(view.context, R.font.unageo_medium)
        private val semiBoldTypeface = ResourcesCompat.getFont(view.context, R.font.unageo_semi_bold)

        fun bind(line: LyricLine, position: Int) {
            textView.text = line.text

            textView.animate().cancel()
            itemView.animate().cancel()
            itemView.background = null
            textView.setTextColor(Color.WHITE)
            textView.translationY = 0f
            textView.alpha = 1f

            val distance = if (activeIndex >= 0) abs(position - activeIndex) else Int.MAX_VALUE

            when {
                position == activeIndex -> {
                    itemView.alpha = ALPHA_ACTIVE
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    textView.typeface = semiBoldTypeface ?: Typeface.DEFAULT_BOLD
                    (itemView as? ViewGroup)?.setPadding(
                        itemView.paddingLeft, dpToPx(12), itemView.paddingRight, dpToPx(12)
                    )
                    textView.alpha = 0.82f
                    textView.translationY = dpToPx(6).toFloat()
                    textView.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(260)
                        .start()
                }
                distance <= 2 -> {
                    itemView.alpha = ALPHA_NEARBY
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    textView.typeface = mediumTypeface ?: Typeface.DEFAULT
                    (itemView as? ViewGroup)?.setPadding(
                        itemView.paddingLeft, dpToPx(10), itemView.paddingRight, dpToPx(10)
                    )
                }
                else -> {
                    itemView.alpha = ALPHA_FAR
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    textView.typeface = mediumTypeface ?: Typeface.DEFAULT
                    (itemView as? ViewGroup)?.setPadding(
                        itemView.paddingLeft, dpToPx(10), itemView.paddingRight, dpToPx(10)
                    )
                }
            }
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * itemView.resources.displayMetrics.density).toInt()
        }
    }
}
