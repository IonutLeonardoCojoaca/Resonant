package com.example.resonant.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.resonant.R
import com.example.resonant.databinding.ItemSettingsChoiceBinding

class ResonantDialog(context: Context) {

    private val dialog = Dialog(context)
    private val view: View = LayoutInflater.from(context)
        .inflate(R.layout.dialog_resonant_custom, null)

    private val sectionView: TextView = view.findViewById(R.id.dialogSection)
    private val titleView:   TextView = view.findViewById(R.id.dialogTitle)
    private val messageView: TextView = view.findViewById(R.id.dialogMessage)
    private val positiveBtn: TextView = view.findViewById(R.id.positiveButton)
    private val negativeBtn: TextView = view.findViewById(R.id.negativeButton)
    private val btnDivider:  View     = view.findViewById(R.id.buttonDivider)
    private val choicesView: LinearLayout = view.findViewById(R.id.dialogChoices)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 48f, displayMetrics
        ).toInt()
        dialog.window?.setLayout(
            screenWidth - marginPx,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun setSection(text: String): ResonantDialog {
        if (text.isNotEmpty()) {
            sectionView.text = text.uppercase()
            sectionView.visibility = View.VISIBLE
        }
        return this
    }

    fun setTitle(text: String): ResonantDialog {
        titleView.text = text
        titleView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        return this
    }

    fun setMessage(text: String): ResonantDialog {
        messageView.text = text
        return this
    }

    fun setPositiveButton(
        text: String,
        onClick: () -> Unit
    ): ResonantDialog {
        positiveBtn.text = text
        positiveBtn.setOnClickListener {
            onClick()
            dialog.dismiss()
        }
        return this
    }

    fun setNegativeButton(
        text: String,
        onClick: (() -> Unit)? = null
    ): ResonantDialog {
        negativeBtn.text = text
        negativeBtn.visibility = View.VISIBLE
        btnDivider.visibility = View.VISIBLE
        negativeBtn.setOnClickListener {
            onClick?.invoke()
            dialog.dismiss()
        }
        return this
    }

    // Acción destructiva — botón positivo en rojo
    fun setDestructive(): ResonantDialog {
        positiveBtn.setTextColor(
            ContextCompat.getColor(
                dialog.context,
                R.color.secondaryColorTheme
            )
        )
        return this
    }

    /**
     * Elección única con las filas del selector de Ajustes: pulsar una la devuelve y cierra. Sin mensaje ni botón
     * de aceptar (la opción es la acción); [selected] marca la actual.
     */
    fun setChoices(labels: List<String>, selected: Int, onPick: (Int) -> Unit): ResonantDialog {
        messageView.visibility = View.GONE
        positiveBtn.visibility = View.GONE
        btnDivider.visibility = View.GONE
        choicesView.visibility = View.VISIBLE
        choicesView.removeAllViews()
        labels.forEachIndexed { index, label ->
            val row = ItemSettingsChoiceBinding.inflate(LayoutInflater.from(dialog.context), choicesView, false)
            row.choiceTitle.text = label
            row.choiceSubtitle.visibility = View.GONE
            row.root.isSelected = index == selected
            row.choiceRadio.isChecked = index == selected
            row.root.setOnClickListener {
                onPick(index)
                dialog.dismiss()
            }
            choicesView.addView(row.root)
        }
        return this
    }

    fun setCancelable(cancelable: Boolean): ResonantDialog {
        dialog.setCancelable(cancelable)
        return this
    }

    fun show() {
        dialog.show()
    }
}