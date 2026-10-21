package com.example.resonant.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.example.resonant.R
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * Diálogo con la estética de PlayMix (tarjeta con brillo cálido, insignia con el degradado y acciones en pastilla),
 * apiladas de mayor a menor peso: principal, secundaria y una terciaria de texto. Opcionalmente con un campo de texto
 * que se valida en línea (sin toasts) y abre el teclado al mostrarse, o con una lista de opciones (única o múltiple).
 */
class PlaymixDialog(private val context: Context) {

    private val dialog = Dialog(context)
    private val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_playmix, null)
    private val card: View = view.findViewById(R.id.playmixDialogCard)
    private val icon: ImageView = view.findViewById(R.id.playmixDialogIcon)
    private val eyebrow: TextView = view.findViewById(R.id.playmixDialogEyebrow)
    private val title: TextView = view.findViewById(R.id.playmixDialogTitle)
    private val message: TextView = view.findViewById(R.id.playmixDialogMessage)
    private val input: EditText = view.findViewById(R.id.playmixDialogInput)
    private val inputMeta: View = view.findViewById(R.id.playmixDialogInputMeta)
    private val inputError: TextView = view.findViewById(R.id.playmixDialogInputError)
    private val inputCounter: TextView = view.findViewById(R.id.playmixDialogInputCounter)
    private val primary: TextView = view.findViewById(R.id.playmixDialogPrimary)
    private val secondary: TextView = view.findViewById(R.id.playmixDialogSecondary)
    private val tertiary: TextView = view.findViewById(R.id.playmixDialogTertiary)
    private val choicesScroll: View = view.findViewById(R.id.playmixDialogChoicesScroll)
    private val choicesList: LinearLayout = view.findViewById(R.id.playmixDialogChoices)

    /** Opción de una lista: título y detalle opcional en una segunda línea. */
    data class Choice(val title: String, val subtitle: String? = null)

    private val checked = linkedSetOf<Int>()

    /** Con campo de texto: devuelve el error a mostrar o null si el valor vale. */
    private var validator: ((String) -> String?)? = null
    private var onInputDone: ((String) -> Unit)? = null
    private var initialValue: String? = null
    private var requireChange = true

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.resources.displayMetrics.widthPixels - dp(40f), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
    }

    fun setIcon(@DrawableRes res: Int) = apply { icon.setImageResource(res) }

    fun setEyebrow(text: String) = apply { eyebrow.text = text.uppercase() }

    fun setTitle(text: String) = apply { title.text = text }

    fun setMessage(text: String) = apply {
        message.text = text
        message.visibility = View.VISIBLE
    }

    /**
     * Campo de texto con [value] seleccionado. [validate] devuelve el error en línea (o null); la acción principal
     * se apaga mientras haya error o (con [requireChange]) el valor no cambie, y la tecla «Hecho» del teclado la ejecuta.
     */
    fun setInput(
        value: String,
        hint: String,
        maxLength: Int,
        requireChange: Boolean = true,
        validate: (String) -> String?
    ) = apply {
        validator = validate
        initialValue = value
        this.requireChange = requireChange
        input.visibility = View.VISIBLE
        inputMeta.visibility = View.VISIBLE
        input.hint = hint
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        input.setText(value)
        input.setSelection(0, value.length)
        inputCounter.text = "${value.length}/$maxLength"
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                inputCounter.text = "${s?.length ?: 0}/$maxLength"
                refreshInputState()
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && primary.isEnabled) {
                primary.performClick()
                true
            } else false
        }
        refreshInputState()
    }

    private fun refreshInputState() {
        val value = input.text?.toString()?.trim().orEmpty()
        val error = validator?.invoke(value)
        inputError.text = error.orEmpty()
        primary.isEnabled = error == null && (!requireChange || value != initialValue?.trim())
        primary.alpha = if (primary.isEnabled) 1f else 0.5f
    }

    /**
     * Elección única: pulsar una opción la devuelve y cierra el diálogo. [selected] marca la actual (-1, ninguna);
     * elegir la que ya estaba marcada también la devuelve, el llamante decide si hay algo que hacer.
     */
    fun setChoices(choices: List<Choice>, selected: Int = -1, onPick: (Int) -> Unit) = apply {
        if (selected in choices.indices) checked += selected
        addChoiceRows(choices, multi = false) { index ->
            onPick(index)
            dialog.dismiss()
        }
    }

    /**
     * Selección múltiple. [validate] recibe los índices marcados y devuelve el aviso a mostrar (o null): la acción
     * principal se apaga mientras haya aviso. Los marcados se leen con [checkedChoices].
     */
    fun setMultiChoices(choices: List<Choice>, validate: (Set<Int>) -> String?) = apply {
        validator = { validate(checked) }
        inputCounter.visibility = View.GONE
        addChoiceRows(choices, multi = true) { index ->
            if (!checked.remove(index)) checked += index
            refreshChoiceRows(multi = true)
            refreshChoicesState()
        }
        refreshChoicesState()
    }

    fun checkedChoices(): Set<Int> = checked.toSet()

    private fun addChoiceRows(choices: List<Choice>, multi: Boolean, onClick: (Int) -> Unit) {
        choicesScroll.visibility = View.VISIBLE
        choicesList.removeAllViews()
        val inflater = LayoutInflater.from(context)
        choices.forEachIndexed { index, choice ->
            val row = inflater.inflate(R.layout.item_mx_dialog_choice, choicesList, false)
            row.findViewById<TextView>(R.id.mxChoiceTitle).text = choice.title
            row.findViewById<TextView>(R.id.mxChoiceSubtitle).apply {
                text = choice.subtitle.orEmpty()
                visibility = if (choice.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            row.findViewById<View>(if (multi) R.id.mxChoiceCheck else R.id.mxChoiceRadio).visibility = View.VISIBLE
            row.setOnClickListener { onClick(index) }
            choicesList.addView(row)
        }
        // Listas largas (fusionar): se desplazan dentro del diálogo en vez de empujar las acciones fuera de pantalla.
        if (choices.size > MAX_VISIBLE_CHOICES) {
            choicesScroll.layoutParams = choicesScroll.layoutParams.apply { height = dp(MAX_CHOICES_HEIGHT_DP) }
        }
        refreshChoiceRows(multi)
    }

    private fun refreshChoiceRows(multi: Boolean) {
        for (index in 0 until choicesList.childCount) {
            val row = choicesList.getChildAt(index)
            val isChecked = index in checked
            row.isSelected = isChecked
            row.stateDescription = when {
                !isChecked -> if (multi) "Sin marcar" else "No seleccionada"
                multi -> "Marcada"
                else -> "Seleccionada"
            }
            if (multi) row.findViewById<MaterialCheckBox>(R.id.mxChoiceCheck).isChecked = isChecked
            else row.findViewById<MaterialRadioButton>(R.id.mxChoiceRadio).isChecked = isChecked
        }
    }

    private fun refreshChoicesState() {
        val error = validator?.invoke("")
        inputError.text = error.orEmpty()
        // Sin aviso no se reserva su línea: la acción queda pegada a la lista.
        inputMeta.visibility = if (error == null) View.GONE else View.VISIBLE
        primary.isEnabled = error == null
        primary.alpha = if (primary.isEnabled) 1f else 0.5f
    }

    /** Acción principal; con campo de texto recibe su valor ya recortado. */
    fun setPrimary(text: String, onClick: (value: String) -> Unit) = apply {
        primary.text = text
        primary.setOnClickListener {
            onClick(input.text?.toString()?.trim().orEmpty())
            dialog.dismiss()
        }
    }

    /** Acción secundaria (pastilla con borde). [destructive] la pinta en rojo, p. ej. «Descartar». */
    fun setSecondary(text: String, destructive: Boolean = false, onClick: () -> Unit) = apply {
        secondary.text = text
        secondary.visibility = View.VISIBLE
        if (destructive) secondary.setTextColor(Color.parseColor("#FF6B6B"))
        secondary.setOnClickListener {
            onClick()
            dialog.dismiss()
        }
    }

    /** Acción discreta de texto (cancelar, seguir editando). */
    fun setTertiary(text: String, onClick: (() -> Unit)? = null) = apply {
        tertiary.text = text
        tertiary.visibility = View.VISIBLE
        tertiary.setOnClickListener {
            onClick?.invoke()
            dialog.dismiss()
        }
    }

    fun show() {
        // Elección única: la opción es la acción, sin botón principal vacío.
        if (primary.text.isNullOrEmpty()) primary.visibility = View.GONE
        if (input.visibility == View.VISIBLE) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            input.requestFocus()
        }
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        dialog.show()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(260).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_VISIBLE_CHOICES = 5
        const val MAX_CHOICES_HEIGHT_DP = 340f
    }
}
