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
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import com.example.resonant.R

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
}