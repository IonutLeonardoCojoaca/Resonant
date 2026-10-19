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
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.example.resonant.R

/**
 * Diálogo con la estética de PlayMix (tarjeta con brillo cálido, insignia con el degradado y acciones en pastilla),
 * apiladas de mayor a menor peso: principal, secundaria y una terciaria de texto. Opcionalmente con un campo de texto
 * que se valida en línea (sin toasts) y abre el teclado al mostrarse.
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

    /** Con campo de texto: devuelve el error a mostrar o null si el valor vale. */
    private var validator: ((String) -> String?)? = null
    private var onInputDone: ((String) -> Unit)? = null
    private var initialValue: String? = null

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
     * se apaga mientras haya error o el valor no cambie, y la tecla «Hecho» del teclado la ejecuta.
     */
    fun setInput(value: String, hint: String, maxLength: Int, validate: (String) -> String?) = apply {
        validator = validate
        initialValue = value
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
        primary.isEnabled = error == null && value != initialValue?.trim()
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
}
