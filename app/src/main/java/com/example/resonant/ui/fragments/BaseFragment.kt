package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.transition.Fade // Asegúrate de importar esto, no android.transition
import androidx.transition.TransitionInflater

open class BaseFragment(layoutRes: Int) : Fragment(layoutRes) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configurar la duración deseada
        val duration = 50L // Un poco más lento para que se aprecie la elegancia

        // 2. Definir la animación de ENTRADA (Cuando este fragment aparece)
        enterTransition = Fade().apply {
            this.duration = duration
        }

        // 3. Definir la animación de SALIDA (Cuando te vas de este fragment a otro)
        exitTransition = Fade().apply {
            this.duration = duration
        }

        // 4. Definir la animación de RETORNO (Cuando pulsas Atrás y vuelves a este)
        reenterTransition = Fade().apply {
            this.duration = duration
        }

        // 5. LA CLAVE DEL ÉXITO: "allowEnterTransitionOverlap"
        // Si es true (por defecto): Los dos fragments se funden a la vez (Crossfade).
        // Si es false: Primero acaba de irse el viejo, y LUEGO empieza a entrar el nuevo.
        allowEnterTransitionOverlap = false
        allowReturnTransitionOverlap = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // YA NO necesitas animar view.alpha aquí.
        // El sistema de transiciones lo hace por ti de forma más eficiente.
    }
}