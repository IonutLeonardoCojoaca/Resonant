package com.example.resonant.ui.fragments

import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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
        view.findViewById<LinearLayout>(R.id.optionCreatePlaylist).setOnClickListener {
            dismiss()
            findNavController().navigate(R.id.createPlaylistFragment)
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val params = window.attributes

        // --- 1. LÓGICA DE POSICIONAMIENTO (Igual que antes) ---
        val density = Resources.getSystem().displayMetrics.density
        val bottomNav = requireActivity().findViewById<View>(R.id.bottom_navigation)

        // Altura del nav o fallback
        val navHeight = if (bottomNav != null && bottomNav.height > 0) {
            bottomNav.height
        } else {
            (80 * density).toInt()
        }

        // Margen estético
        val margin = (density).toInt()

        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.y = navHeight + margin
        // -------------------------------------------------------

        // --- 2. CONFIGURACIÓN DE INTERACCIÓN Y CIERRE ---

        // Quitamos la oscuridad del fondo
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Permitimos que los clics pasen a la actividad de fondo (BottomNav)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        // Pedimos al sistema que nos avise si tocan fuera
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)

        // Detectamos ese aviso "fuera" para cerrar el diálogo
        window.decorView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true // Consumimos el evento del listener (no el del sistema)
            } else {
                // Si tocan DENTRO del diálogo, dejamos que el sistema lo maneje normal
                // (para que funcionen tus botones internos como 'Crear Playlist')
                v.performClick() // Buena práctica para accesibilidad
                false
            }
        }

        window.attributes = params
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Esto se ejecutará tanto si pulsas la opción como si pulsas fuera (en la X)
        onDismissListener?.invoke()
    }
}