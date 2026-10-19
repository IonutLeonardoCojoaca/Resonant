package com.example.resonant.ui.components

import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.resonant.R
import com.example.resonant.playback.OfflineCollectionStore
import com.example.resonant.ui.viewmodels.DownloadedCollectionItem

/**
 * Secciones Playlists, Playmix y Álbumes de la pantalla de Descargas (collection-queue-offline): por colección,
 * su número de canciones, el espacio que ocupa y su estado. Pulsar una fila la abre (sin conexión, con lo guardado).
 */
class DownloadedCollectionsSection(
    private val container: LinearLayout,
    private val onOpen: (DownloadedCollectionItem) -> Unit
) {

    fun bind(items: List<DownloadedCollectionItem>) {
        container.removeAllViews()
        container.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) return
        SECTIONS.forEach { (type, title) ->
            val ofType = items.filter { it.summary.collectionType == type }
            if (ofType.isEmpty()) return@forEach
            container.addView(sectionTitle(title))
            ofType.forEach { container.addView(row(it)) }
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(container.context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.white))
        typeface = ResourcesCompat.getFont(context, R.font.unageo_bold)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val h = dp(16)
        setPadding(h, dp(10), h, dp(4))
    }

    private fun row(item: DownloadedCollectionItem): TextView = TextView(container.context, null, 0, R.style.OptionRow).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val summary = item.summary
        val songs = if (summary.songCount == 1) "1 canción" else "${summary.songCount} canciones"
        val size = Formatter.formatShortFileSize(context, item.sizeBytes)
        text = "${summary.title.ifBlank { "Sin título" }}\n$songs · $size · ${statusText(item)}"
        setCompoundDrawablesRelativeWithIntrinsicBounds(iconFor(summary.collectionType), 0, 0, 0)
        setOnClickListener { onOpen(item) }
    }

    private fun statusText(item: DownloadedCollectionItem): String = when {
        item.unavailable -> "Ya no disponible"
        item.pendingUpdate -> "Pendiente de actualizar"
        !item.isComplete -> "Descargando ${item.summary.completedCount}/${item.summary.songCount}"
        else -> "Descargada"
    }

    private fun iconFor(type: String): Int = when (type) {
        OfflineCollectionStore.TYPE_ALBUM -> R.drawable.ic_disc
        else -> R.drawable.ic_playlist_stack
    }

    private fun dp(value: Int): Int = (value * container.resources.displayMetrics.density).toInt()

    private companion object {
        val SECTIONS = listOf(
            OfflineCollectionStore.TYPE_PLAYLIST to "Playlists",
            OfflineCollectionStore.TYPE_PLAYMIX to "Playmix",
            OfflineCollectionStore.TYPE_ALBUM to "Álbumes"
        )
    }
}
