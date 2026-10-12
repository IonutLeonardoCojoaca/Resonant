package com.example.resonant.ui.fragments

import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.ui.activities.MainActivity
import com.example.resonant.unisound.UnisoundManager

class CreationMenuDialog : DialogFragment() {

    var onDismissListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_creation_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. CERRAR AL TOCAR EL FONDO OSCURO
        // El ID 'viewBackground' debe estar en tu XML raíz (ver abajo)
        view.findViewById<View>(R.id.viewBackground).setOnClickListener {
            dismiss()
        }

        // 2. EVITAR QUE EL CLIC EN LA TARJETA CIERRE EL DIÁLOGO
        view.findViewById<View>(R.id.cardContent).setOnClickListener {
            // Consumimos el evento
        }

        // Tu botón de acción
        view.findViewById<LinearLayout>(R.id.optionCreatePlaylist).setOnClickListener {
            dismiss()
            findNavController().navigate(R.id.createPlaylistFragment)
        }

        view.findViewById<LinearLayout>(R.id.optionCreatePlaymix).setOnClickListener {
            dismiss()
            findNavController().navigate(R.id.action_global_to_createPlaymixFragment)
        }

        bindUnisound(view)
    }

    /**
     * Unisound is one entry; tapping it swaps the menu for a second floating card with
     * "Crear sala" and "Unirse con código". Already in a room, it goes straight to the room.
     */
    private fun bindUnisound(view: View) {
        val main = activity as? MainActivity
        val inRoom = UnisoundManager.get(requireContext()).state.value != null
        val visible = inRoom || CapabilitiesManager.isUnisoundEnabled()
        listOf(R.id.unisoundDivider, R.id.optionUnisound).forEach { id ->
            view.findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (inRoom) {
            view.findViewById<TextView>(R.id.optionUnisoundSubtitle).text = "Estás en una sala · toca para volver"
        }
        val mainCard = view.findViewById<View>(R.id.cardContent)
        val unisoundCard = view.findViewById<View>(R.id.unisoundCard)
        unisoundCard.setOnClickListener { }

        view.findViewById<View>(R.id.optionUnisound).setOnClickListener {
            if (inRoom) {
                dismiss()
                main?.openUnisoundRoom()
            } else {
                swapCards(hide = mainCard, show = unisoundCard)
            }
        }
        view.findViewById<View>(R.id.optionUnisoundCreate).setOnClickListener {
            dismiss()
            main?.createUnisoundRoom()
        }
        view.findViewById<View>(R.id.optionUnisoundJoin).setOnClickListener {
            dismiss()
            main?.showUnisoundJoin()
        }
    }

    private fun swapCards(hide: View, show: View) {
        val shift = SWAP_SHIFT_DP * resources.displayMetrics.density
        hide.animate().alpha(0f).translationY(shift).setDuration(SWAP_DURATION_MS).withEndAction {
            hide.visibility = View.GONE
            show.alpha = 0f
            show.translationY = shift
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).translationY(0f).setDuration(SWAP_DURATION_MS).start()
        }.start()
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val params = window.attributes
        val view = view ?: return

        // --- 1. OBTENER ALTURA SEGURA DEL NAV ---
        val density = Resources.getSystem().displayMetrics.density
        val bottomNav = requireActivity().findViewById<View>(R.id.bottom_navigation)

        // Si bottomNav.height es 0 (común), usamos 80dp como fallback estándar
        val navHeight = if (bottomNav != null && bottomNav.height > 0) {
            bottomNav.height
        } else {
            (80 * density).toInt()
        }

        // --- 2. APLICAR MARGEN VISUAL AL CONTENIDO ---
        // Esto fuerza a tu tarjeta a subir, independientemente de la ventana
        val menuContainer = view.findViewById<View>(R.id.menuContainer) // El LinearLayout que envuelve la Card
        val layoutParams = menuContainer.layoutParams as ViewGroup.MarginLayoutParams
        // Mantenemos el padding que pusiste en XML (12dp) y LE SUMAMOS la altura del nav
        layoutParams.bottomMargin = navHeight
        menuContainer.layoutParams = layoutParams

        // --- 3. CONFIGURACIÓN DE LA VENTANA (PARA LOS CLICS) ---
        params.gravity = Gravity.BOTTOM
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT

        // IMPORTANTE:
        // Aunque hemos subido la vista visualmente con margin,
        // también subimos la ventana lógica para liberar los clics del BottomNav.
        params.y = navHeight

        // Quitamos la sombra del sistema (usaremos la nuestra del XML)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Permitimos clics en lo que quede "fuera" de la ventana (el BottomNav)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        window.attributes = params
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke()
    }

    private companion object {
        const val SWAP_DURATION_MS = 160L
        const val SWAP_SHIFT_DP = 16f
    }
}
