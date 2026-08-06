package com.example.resonant.ui.bottomsheets

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import com.example.resonant.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base común para que la superficie Material nunca pinte un fondo claro detrás
 * del drawable propio de cada sheet.
 *
 * El fondo se limpia en [onStart], cuando `design_bottom_sheet` ya existe. Esto
 * conserva el scrim, el comportamiento de arrastre, la accesibilidad y el
 * ajuste por teclado del diálogo.
 */
abstract class ResonantBottomSheetDialogFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onStart() {
        super.onStart()

        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val container = (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return

        container.setBackgroundColor(Color.TRANSPARENT)
        ViewCompat.setBackgroundTintList(
            container,
            ColorStateList.valueOf(Color.TRANSPARENT)
        )
    }
}
