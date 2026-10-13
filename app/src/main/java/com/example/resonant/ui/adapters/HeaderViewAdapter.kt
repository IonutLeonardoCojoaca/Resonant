package com.example.resonant.ui.adapters

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter de una sola fila que muestra una vista ya inflada (cabecera fija de una
 * lista). Pensado para usarse dentro de un ConcatAdapter delante del adapter real,
 * así la cabecera hace scroll con la lista sin perder el reciclado de filas.
 */
class HeaderViewAdapter(private val header: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var isVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount(): Int = if (isVisible) 1 else 0

    override fun getItemViewType(position: Int): Int = header.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        (header.parent as? ViewGroup)?.removeView(header)
        if (header.layoutParams == null) {
            header.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return object : RecyclerView.ViewHolder(header) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
