package com.example.resonant.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.PlaymixSharingManager
import com.example.resonant.ui.adapters.HeaderViewAdapter
import com.example.resonant.ui.adapters.PlaymixListAdapter
import kotlinx.coroutines.launch

/**
 * Sección "Me gustan" bajo las PlayMix propias en la biblioteca (playmix-sharing-discovery): las Playmix con
 * me gusta que el usuario aún puede ver. Oculta con la capacidad apagada o sin elementos.
 */
class LikedPlaymixesSection(private val fragment: Fragment) {

    private val title: TextView = TextView(fragment.requireContext()).apply {
        id = View.generateViewId()
        text = "Me gustan"
        setTextColor(context.getColor(R.color.white))
        textSize = 16f
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.unageo_semi_bold)
        val density = resources.displayMetrics.density
        setPadding((16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
    }

    private val header = HeaderViewAdapter(title).apply { isVisible = false }

    private val list = PlaymixListAdapter(
        onClick = { playmix -> open(playmix.id) },
        onOptionsClick = { playmix -> open(playmix.id) }
    )

    val adapters: List<RecyclerView.Adapter<out RecyclerView.ViewHolder>> = listOf(header, list)

    /** `true` si la sección muestra alguna Playmix (la página no está vacía aunque no haya propias). */
    val hasItems: Boolean get() = header.isVisible

    /** Se llama cuando cambia [hasItems], para que la página recalcule su estado vacío. */
    var onChanged: (() -> Unit)? = null

    fun refresh() {
        if (!CapabilitiesManager.isPlaymixSharingEnabled()) {
            header.isVisible = false
            list.submitList(emptyList())
            onChanged?.invoke()
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val items = try {
                PlaymixSharingManager(fragment.requireContext()).getLikedPlaymixes(cursor = null, limit = 50).items.orEmpty()
            } catch (e: Exception) {
                Log.w("LikedPlaymixes", "liked unavailable: ${e.javaClass.simpleName}")
                return@launch
            }
            if (!fragment.isAdded) return@launch
            header.isVisible = items.isNotEmpty()
            list.submitList(items)
            onChanged?.invoke()
        }
    }

    private fun open(playmixId: String) {
        fragment.findNavController().navigate(
            R.id.action_global_to_playmixDetailFragment,
            Bundle().apply { putString("playmixId", playmixId) }
        )
    }
}
