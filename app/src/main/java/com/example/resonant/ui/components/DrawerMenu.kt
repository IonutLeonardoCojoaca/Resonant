package com.example.resonant.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.resonant.R

/**
 * Menú lateral de la marca: rellena cada fila (icono y nombre), marca la pantalla actual y anima la apertura (las
 * filas entran en cascada y la pantalla de detrás se convierte en una tarjeta redondeada). Todo se busca dentro de
 * [drawer]: Inicio tiene ids iguales (p. ej. `searchButton`) y no deben confundirse.
 */
class DrawerMenu(private val drawer: View, private val mainContent: View) {

    /** Una fila: su include, nombre, icono y las pantallas en las que se marca como actual. */
    data class Entry(val rowId: Int, val label: String, val icon: Int, val destinations: Set<Int>)

    private val context = drawer.context
    private val density = context.resources.displayMetrics.density

    val entries = listOf(
        Entry(R.id.homeButton, "Inicio", R.drawable.ic_menu_home, setOf(R.id.homeFragment)),
        Entry(R.id.exploreButton, "Explorar", R.drawable.ic_menu_explore, setOf(R.id.exploreFragment)),
        Entry(R.id.searchButton, "Buscar", R.drawable.ic_search, setOf(R.id.searchFragment)),
        Entry(R.id.savedButton, "Biblioteca", R.drawable.ic_menu_saved, setOf(R.id.savedFragment)),
        Entry(R.id.playmixButton, "PlayMix", R.drawable.ic_playmix, setOf(R.id.playmixListFragment, R.id.playmixDetailFragment)),
        Entry(R.id.downloadsButton, "Descargas", R.drawable.ic_download, setOf(R.id.downloadedSongsFragment)),
        Entry(R.id.historyButton, "Historial", R.drawable.ic_recent, setOf(R.id.historyFragment)),
        Entry(R.id.statsButton, "Estadísticas", R.drawable.ic_rs_stats, setOf(R.id.listeningStatsFragment)),
        Entry(R.id.settingsButton, "Ajustes", R.drawable.ic_settings, setOf(R.id.settingsFragment))
    )

    /** Lo que entra en cascada al abrir, de arriba abajo. */
    private val cascade: List<View>

    private var cornerRadius = 0f

    init {
        entries.forEach { entry ->
            val row = row(entry.rowId) ?: return@forEach
            row.findViewById<TextView>(R.id.drawerRowLabel).text = entry.label
            row.findViewById<ImageView>(R.id.drawerRowIcon).setImageResource(entry.icon)
            row.contentDescription = entry.label
        }
        val items = drawer.findViewById<LinearLayout>(R.id.drawerItems)
        cascade = buildList {
            drawer.findViewById<View>(R.id.drawerBrandRow)?.let(::add)
            drawer.findViewById<View>(R.id.headerDrawer)?.let(::add)
            for (i in 0 until (items?.childCount ?: 0)) add(items.getChildAt(i))
            drawer.findViewById<View>(R.id.footerLayout)?.let(::add)
        }
        // La pantalla de detrás se recorta con esquinas que crecen al abrir el menú.
        mainContent.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
    }

    fun row(rowId: Int): View? = drawer.findViewById(rowId)

    /** Marca la fila de la pantalla actual (o ninguna si no está en el menú). */
    fun setCurrentDestination(destinationId: Int) {
        val white = ContextCompat.getColor(context, R.color.white)
        entries.forEach { entry ->
            val row = row(entry.rowId) ?: return@forEach
            val selected = destinationId in entry.destinations
            row.isSelected = selected
            row.findViewById<ImageView>(R.id.drawerRowIcon).imageTintList =
                ColorStateList.valueOf(if (selected) Color.WHITE else white)
            row.findViewById<TextView>(R.id.drawerRowLabel).typeface = context.resources.getFont(
                if (selected) R.font.unageo_bold else R.font.unageo_semi_bold
            )
        }
    }

    /**
     * Apertura del menú ([offset] 0..1): cada elemento entra desde la izquierda algo después que el anterior, y la
     * pantalla de detrás se desplaza, encoge y redondea sus esquinas.
     */
    fun onSlide(drawerView: View, offset: Float) {
        val count = cascade.size
        val spread = 0.45f
        cascade.forEachIndexed { index, view ->
            val start = spread * index / count
            val local = ((offset - start) / (1f - spread)).coerceIn(0f, 1f)
            val eased = 1f - (1f - local) * (1f - local) * (1f - local)
            view.translationX = -CASCADE_DP * density * (1f - eased)
            view.alpha = eased
        }
        mainContent.translationX = drawerView.width * offset
        val scale = 1f - offset * MAIN_SCALE
        mainContent.scaleX = scale
        mainContent.scaleY = scale
        cornerRadius = MAIN_CORNER_DP * density * offset
        mainContent.clipToOutline = offset > 0f
        mainContent.invalidateOutline()
        mainContent.elevation = MAIN_ELEVATION_DP * density * offset
    }

    /** Menú cerrado: todo vuelve a su sitio (la aurora se para sola al ocultarse el menú). */
    fun onClosed() {
        mainContent.translationX = 0f
        mainContent.scaleX = 1f
        mainContent.scaleY = 1f
        cornerRadius = 0f
        mainContent.clipToOutline = false
        mainContent.elevation = 0f
        cascade.forEach { it.translationX = 0f; it.alpha = 1f }
    }

    /** Ancho del menú: el 85 % de la pantalla, como siempre. */
    fun applyWidth(container: View, screenWidth: Int) {
        container.layoutParams = (container.layoutParams as ViewGroup.LayoutParams).apply {
            width = (screenWidth * 0.85).toInt()
        }
    }

    private companion object {
        const val CASCADE_DP = 48f
        const val MAIN_SCALE = 0.08f
        const val MAIN_CORNER_DP = 32f
        const val MAIN_ELEVATION_DP = 12f
    }
}
