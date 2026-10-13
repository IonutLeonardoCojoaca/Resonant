package com.example.resonant.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import com.example.resonant.R
import com.example.resonant.ui.explore.ExploreQuickCard
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Controla `view_create_name_field`: nombre grande con subrayado de acento al enfocar,
 * contador, error en línea, ideas de nombre y "Hecho" del teclado como atajo de crear.
 */
class CreateNameField(
    root: View,
    accent: ResonantAccent,
    hint: String,
    private val maxLength: Int,
    suggestions: List<String>,
    private val onChanged: (String) -> Unit,
    private val onDone: () -> Unit
) {
    val input: EditText = root.findViewById(R.id.createNameInput)
    private val underline: View = root.findViewById(R.id.createNameUnderline)
    private val counter: TextView = root.findViewById(R.id.createNameCounter)
    private val error: TextView = root.findViewById(R.id.createNameError)

    val text: String get() = input.text?.toString()?.trim().orEmpty()
    val isValid: Boolean get() = text.isNotEmpty() && text.length <= maxLength

    init {
        val idleLine = ContextCompat.getColor(root.context, R.color.discTheme)
        input.hint = hint
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        input.setOnFocusChangeListener { _, hasFocus ->
            underline.setBackgroundColor(if (hasFocus) accent.color else idleLine)
        }
        input.doOnTextChanged { value, _, _, _ ->
            counter.text = "${value?.length ?: 0}/$maxLength"
            if (error.isVisible) error.isVisible = false
            onChanged(text)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onDone()
                true
            } else {
                false
            }
        }
        counter.text = "0/$maxLength"

        val chipGroup = root.findViewById<ChipGroup>(R.id.createNameSuggestions)
        val chipBackground = ContextCompat.getColorStateList(root.context, R.color.cardsTheme)
        val chipStroke = ContextCompat.getColorStateList(root.context, R.color.discTheme)
        val chipText = ContextCompat.getColorStateList(root.context, R.color.white)
        val chipFont = ResourcesCompat.getFont(root.context, R.font.unageo_medium)
        suggestions.forEach { suggestion ->
            chipGroup.addView(Chip(root.context).apply {
                text = suggestion
                chipBackgroundColor = chipBackground
                chipStrokeColor = chipStroke
                chipStrokeWidth = root.resources.displayMetrics.density
                setTextColor(chipText)
                typeface = chipFont
                setEnsureMinTouchTargetSize(false)
                setOnClickListener {
                    input.setText(suggestion)
                    input.setSelection(input.text.length)
                }
            })
        }
    }

    /** Valida y muestra el motivo en línea; devuelve si el nombre es usable. */
    fun validate(): Boolean {
        val message = when {
            text.isEmpty() -> "Ponle un nombre para continuar"
            text.length > maxLength -> "Máximo $maxLength caracteres"
            else -> null
        }
        error.text = message
        error.isVisible = message != null
        return message == null
    }

    /** Enfoca el nombre y abre el teclado (al entrar en la pantalla). */
    fun focusWithKeyboard() {
        input.requestFocus()
        input.postDelayed({
            val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    fun hideKeyboard() {
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }
}

/**
 * Portada de vista previa de las pantallas de creación: reutiliza la tarjeta de acceso
 * rápido de Explorar (`view_explore_quick_card`) y escribe en ella el nombre en directo.
 */
object CreateCoverPreview {

    fun bind(card: View, accent: ResonantAccent, eyebrow: String, placeholder: String) {
        ExploreQuickCard.bind(card, ExploreQuickCard.Spec(eyebrow, placeholder, accent)) {}
        // Es una vista previa: ni se pulsa ni lleva la flecha de "abrir".
        card.setOnClickListener(null)
        card.isClickable = false
        card.isFocusable = false
        card.stateListAnimator = null
        card.findViewById<View>(R.id.quickCardBadge).isVisible = false
        card.findViewById<TextView>(R.id.quickCardTitle).apply {
            maxLines = 2
            textSize = 16f
        }
        card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ExploreQuickCard.animateIn(card, 0)
    }

    fun setTitle(card: View, title: String) {
        card.findViewById<TextView>(R.id.quickCardTitle).text = title
    }

    fun setEyebrow(card: View, eyebrow: String) {
        card.findViewById<TextView>(R.id.quickCardEyebrow).text = eyebrow
    }
}

/** Botón principal de las pantallas de creación: acento, habilitado según validez y estado "Creando…". */
object CreateCta {

    fun style(button: MaterialButton, accent: ResonantAccent) {
        button.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(accent.color, Color.BLACK, 0.28f))
    }

    fun setEnabled(button: MaterialButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    fun setLoading(button: MaterialButton, loading: Boolean, idleText: String) {
        button.text = if (loading) "Creando…" else idleText
        button.isEnabled = !loading
        button.alpha = if (loading) 0.75f else 1f
    }
}
