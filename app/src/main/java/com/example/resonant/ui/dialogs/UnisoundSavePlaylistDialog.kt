package com.example.resonant.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.resonant.R

/**
 * "Guardar como playlist" with an optional name, styled like [ResonantDialog]
 * (same `dialog_resonant_custom` layout plus a text field). Empty name = server default.
 */
class UnisoundSavePlaylistDialog(context: Context, private val onSave: (name: String?) -> Unit) {
    private val dialog = Dialog(context)

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_resonant_custom, null)
        view.findViewById<TextView>(R.id.dialogSection).apply {
            text = "UNISOUND"
            visibility = View.VISIBLE
        }
        view.findViewById<TextView>(R.id.dialogTitle).text = "Guardar como playlist"
        val message = view.findViewById<TextView>(R.id.dialogMessage)
        message.text = "Lo que ha sonado en la sala quedará en tu biblioteca. Si vuelves a guardar, se añaden solo las nuevas."

        val input = EditText(context).apply {
            hint = "Nombre (opcional)"
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))
            setSingleLine()
            typeface = ResourcesCompat.getFont(context, R.font.unageo_regular)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setHintTextColor(ContextCompat.getColor(context, R.color.textTheme))
            background = ContextCompat.getDrawable(context, R.drawable.bg_unisound_input)
            val padding = dp(context, 14f)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        (message.parent as LinearLayout).addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48f)).apply {
                topMargin = dp(context, 14f)
            }
        )

        view.findViewById<TextView>(R.id.positiveButton).apply {
            text = "Guardar"
            setOnClickListener {
                onSave(input.text?.toString()?.trim()?.takeIf(String::isNotEmpty))
                dialog.dismiss()
            }
        }
        view.findViewById<TextView>(R.id.negativeButton).apply {
            text = "Cancelar"
            visibility = View.VISIBLE
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<View>(R.id.buttonDivider).visibility = View.VISIBLE

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            context.resources.displayMetrics.widthPixels - dp(context, 48f),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun show() = dialog.show()

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private companion object {
        const val MAX_NAME_LENGTH = 100
    }
}
