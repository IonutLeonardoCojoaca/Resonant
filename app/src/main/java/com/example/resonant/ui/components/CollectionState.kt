package com.example.resonant.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.example.resonant.R
import com.google.android.material.button.MaterialButton

/**
 * Binds `view_collection_state`: estado vacío o de error de una lista, con el acento
 * de la sección en el icono y en la acción (si la hay).
 */
object CollectionState {

    fun show(
        root: View,
        accent: ResonantAccent,
        @DrawableRes icon: Int,
        title: String,
        subtitle: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        root.findViewById<View>(R.id.stateIconBadge).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent.color, 0x2E))
            setStroke((1 * root.resources.displayMetrics.density).toInt(), ColorUtils.setAlphaComponent(accent.color, 0x55))
        }
        root.findViewById<ImageView>(R.id.stateIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(accent.color)
        }
        root.findViewById<TextView>(R.id.stateTitle).text = title
        root.findViewById<TextView>(R.id.stateSubtitle).text = subtitle
        root.findViewById<MaterialButton>(R.id.stateAction).apply {
            isVisible = actionText != null && onAction != null
            text = actionText
            val fill = ColorUtils.blendARGB(accent.color, Color.BLACK, 0.3f)
            backgroundTintList = ColorStateList.valueOf(fill)
            // Acentos claros (lima de Descargas, oro de Historial): texto oscuro para que se lea.
            val onFill = if (ColorUtils.calculateLuminance(fill) > 0.3) Color.BLACK else Color.WHITE
            setTextColor(onFill)
            iconTint = ColorStateList.valueOf(onFill)
            setOnClickListener { onAction?.invoke() }
        }
        root.isVisible = true
    }

    fun hide(root: View) {
        root.isVisible = false
    }
}
