package com.example.resonant.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import com.example.resonant.R
import kotlin.math.roundToInt

/**
 * Botones `TopBar.*.OnImage` de una barra que pasa de transparente (sobre la foto) a opaca
 * (`primaryColorTheme`) al hacer scroll: con la barra opaca el blanco literal desaparece en el tema
 * claro, así que el icono se mezcla hacia el color de texto del tema y el círculo translúcido se
 * desvanece con la misma fracción con la que aparece el fondo de la barra.
 */
object OnImageTopBar {

    /** Hace mutables los fondos (son drawables compartidos) para poder desvanecerlos. */
    fun prepare(vararg buttons: ImageView) {
        buttons.forEach { it.background?.mutate() }
        update(0f, *buttons)
    }

    /** [fraction] 0 = sobre la foto (variante OnImage), 1 = barra opaca (variante del tema). */
    fun update(fraction: Float, vararg buttons: ImageView) {
        if (buttons.isEmpty()) return
        val f = fraction.coerceIn(0f, 1f)
        val themeIcon = ContextCompat.getColor(buttons[0].context, R.color.white)
        val tint = ColorStateList.valueOf(ColorUtils.blendARGB(Color.WHITE, themeIcon, f))
        val circleAlpha = ((1f - f) * 255).roundToInt()
        buttons.forEach {
            ImageViewCompat.setImageTintList(it, tint)
            it.background?.alpha = circleAlpha
        }
    }
}
