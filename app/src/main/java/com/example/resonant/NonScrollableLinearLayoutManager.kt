package com.example.resonant

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager

class NonScrollableLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun canScrollVertically(): Boolean = false
}