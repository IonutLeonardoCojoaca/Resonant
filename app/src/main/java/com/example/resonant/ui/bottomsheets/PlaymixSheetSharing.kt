package com.example.resonant.ui.bottomsheets

import android.view.View
import android.widget.TextView
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixVisibility

/**
 * Filas de compartir y descubrir de [PlaymixOptionsBottomSheet] (playmix-sharing-discovery).
 * - Dueño: Visibilidad, Ordenar por…, Fusionar con…, Duplicar y Compartir.
 * - Quien no es dueño: Copiar a mi biblioteca, Me gusta y Compartir.
 * Cada callback null oculta su fila.
 */
class PlaymixSheetSharing(
    val isOwner: Boolean,
    val visibility: String,
    val isLiked: Boolean = false,
    val onLikeClick: (() -> Unit)? = null,
    val onShareClick: (() -> Unit)? = null,
    val onVisibilityClick: (() -> Unit)? = null,
    val onDuplicateClick: (() -> Unit)? = null,
    val onSortClick: (() -> Unit)? = null,
    val onMergeClick: (() -> Unit)? = null
) {
    fun bind(root: View, dismiss: () -> Unit) {
        row(root, R.id.playmixLikeOptionButton, onLikeClick.takeUnless { isOwner }, dismiss)?.let { like ->
            like.text = if (isLiked) "Ya no me gusta" else "Me gusta"
            OptionRows.setIcon(like, if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        }
        row(root, R.id.playmixShareOptionButton, onShareClick, dismiss)
        row(root, R.id.playmixVisibilityOptionButton, onVisibilityClick.takeIf { isOwner }, dismiss)?.let {
            it.text = "Visibilidad: ${label(visibility)}"
        }
        row(root, R.id.playmixDuplicateOptionButton, onDuplicateClick, dismiss)?.let {
            it.text = if (isOwner) "Duplicar" else "Copiar a mi biblioteca"
        }
        row(root, R.id.playmixSortOptionButton, onSortClick.takeIf { isOwner }, dismiss)
        row(root, R.id.playmixMergeOptionButton, onMergeClick, dismiss)
    }

    private fun row(root: View, id: Int, action: (() -> Unit)?, dismiss: () -> Unit): TextView? {
        val view = root.findViewById<TextView>(id) ?: return null
        view.visibility = if (action != null) View.VISIBLE else View.GONE
        if (action == null) return null
        view.setOnClickListener {
            dismiss()
            action()
        }
        return view
    }

    companion object {
        fun label(visibility: String): String = when (visibility) {
            PlaymixVisibility.PUBLIC -> "Pública"
            PlaymixVisibility.UNLISTED -> "Con enlace"
            else -> "Privada"
        }
    }
}
