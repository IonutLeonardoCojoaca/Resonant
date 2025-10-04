package com.example.resonant

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

class ExpandableHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val expandSpec = MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST)
        super.onMeasure(widthSpec, expandSpec)
    }
}