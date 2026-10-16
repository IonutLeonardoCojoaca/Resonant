package com.example.resonant.ui.detail

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Fija el ancho de cada tarjeta de una lista horizontal de las fichas. Los items compartidos
 * (p. ej. `item_album`) son `match_parent` y en horizontal ocuparían toda la pantalla.
 */
fun RecyclerView.fixedCardWidth(widthDp: Int) {
    val px = (widthDp * resources.displayMetrics.density).toInt()
    addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            val params = view.layoutParams ?: return
            if (params.width != px) {
                params.width = px
                view.layoutParams = params
            }
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
    })
}
