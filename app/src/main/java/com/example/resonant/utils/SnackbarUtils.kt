package com.example.resonant.utils

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.example.resonant.R
import com.google.android.material.snackbar.Snackbar

object SnackbarUtils {

    fun Fragment.showResonantSnackbar(
        text: String,
        colorRes: Int,
        iconRes: Int
    ) {
        val activity = requireActivity()
        val miniPlayerView = activity.findViewById<View>(R.id.mini_player)
        val anchorId = if (miniPlayerView?.visibility == View.VISIBLE) R.id.mini_player else R.id.bottom_navigation
        val snackbar = Snackbar
            .make(activity.findViewById(R.id.main), text, Snackbar.LENGTH_LONG)
            .setAnchorView(anchorId)

        val snackbarView = snackbar.view

        // Márgenes horizontales personalizados
        val params = snackbarView.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            params.setMargins(40, params.topMargin, 40, (params.bottomMargin - 15))
            snackbarView.layoutParams = params
        }

        // Color personalizado
        snackbar.setBackgroundTint(ContextCompat.getColor(requireContext(), colorRes))
        snackbar.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        // Tipografía y icono (siempre igual)
        val snackbarTextView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        val typeface = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        snackbarTextView.typeface = typeface

        val icon = ContextCompat.getDrawable(requireContext(), iconRes)
        icon?.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        snackbarTextView.setCompoundDrawables(icon, null, null, null)
        snackbarTextView.compoundDrawablePadding = 24

        snackbar.show()
    }

}