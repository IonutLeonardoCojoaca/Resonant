package com.example.resonant.utils

import android.view.View
import com.example.resonant.R

/**
 * Pantallas de MainActivity dibujadas detrás de la barra de estado (edge-to-edge real): los fondos, portadas y
 * degradados llegan hasta arriba y solo se aparta lo interactivo. MainActivity lo aplica a cada pantalla del NavHost.
 *
 * - Barras superiores ([BAR_IDS]): ganan la altura de la barra de estado como padding (y como altura si es fija,
 *   p. ej. TopBar de 56dp). Si van fijadas en un CollapsingToolbarLayout (`toolbarId`), su altura plegada crece igual.
 * - Contenido bajo una barra superpuesta ([CONTENT_IDS]): solo si ya reservaba sitio para ella (paddingTop ≥ 48dp).
 * - [GROW_IDS]: degradados fijos bajo la barra, que solo crecen.
 * - Pantallas sin barra reconocible: se aparta la raíz entera; su fondo cubre la franja, del mismo color que la pantalla.
 *
 * Idempotente: guarda los valores originales en un tag y siempre suma sobre ellos.
 */
object StatusBarInsets {

    private val BAR_IDS = intArrayOf(R.id.topBar, R.id.superiorToolbar, R.id.detailToolbar, R.id.searchHeader)
    private val CONTENT_IDS = intArrayOf(R.id.editorContent, R.id.contentScroll, R.id.ariaChatRecyclerView)
    private val GROW_IDS = intArrayOf(R.id.ariaChatTopFade)
    private const val OVERLAY_SPACE_MIN_DP = 48
    /** Aire extra entre la barra de estado y los elementos de la pantalla. */
    private const val EXTRA_SPACING_DP = 8

    fun applyToScreen(root: View, statusBarInset: Int) {
        val density = root.resources.displayMetrics.density
        val inset = if (statusBarInset > 0) statusBarInset + (EXTRA_SPACING_DP * density).toInt() else 0
        var hasBar = false
        BAR_IDS.forEach { id ->
            root.findViewById<View>(id)?.let {
                offset(it, inset, padTop = true, grow = true)
                hasBar = true
            }
        }
        if (!hasBar) {
            offset(root, inset, padTop = true, grow = false)
            return
        }
        val overlaySpaceMin = (OVERLAY_SPACE_MIN_DP * density).toInt()
        CONTENT_IDS.forEach { id ->
            root.findViewById<View>(id)?.let {
                if (originalOf(it)[0] >= overlaySpaceMin) offset(it, inset, padTop = true, grow = false)
            }
        }
        GROW_IDS.forEach { id -> root.findViewById<View>(id)?.let { offset(it, inset, padTop = false, grow = true) } }
    }

    private fun offset(view: View, inset: Int, padTop: Boolean, grow: Boolean) {
        val (originalPaddingTop, originalHeight) = originalOf(view).let { it[0] to it[1] }
        if (padTop && view.paddingTop != originalPaddingTop + inset) {
            view.setPadding(view.paddingLeft, originalPaddingTop + inset, view.paddingRight, view.paddingBottom)
        }
        val params = view.layoutParams ?: return
        if (grow && originalHeight > 0 && params.height != originalHeight + inset) {
            params.height = originalHeight + inset
            view.layoutParams = params
        }
    }

    /** [paddingTop, altura de layoutParams] tal como venían del XML. */
    private fun originalOf(view: View): IntArray =
        view.getTag(R.id.status_bar_inset_original) as? IntArray
            ?: intArrayOf(view.paddingTop, view.layoutParams?.height ?: 0)
                .also { view.setTag(R.id.status_bar_inset_original, it) }
}
