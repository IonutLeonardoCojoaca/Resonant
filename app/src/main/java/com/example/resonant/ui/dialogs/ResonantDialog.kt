package com.example.resonant.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.example.resonant.R

class ResonantDialog(context: Context) {

    private val dialog = Dialog(context)
    private val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_resonant_custom, null)

    // Referencias a las vistas del XML
    private val titleView: TextView = view.findViewById(R.id.dialogTitle)
    private val messageView: TextView = view.findViewById(R.id.dialogMessage)
    private val positiveBtn: Button = view.findViewById(R.id.positiveButton)
    private val negativeBtn: Button = view.findViewById(R.id.negativeButton)

    init {
        // Configuración base para que el fondo sea transparente y se vea tu diseño redondeado
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Hacer que el diálogo sea ancho (match_parent con márgenes)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
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

    fun setPositiveButton(text: String, onClick: () -> Unit): ResonantDialog {
        positiveBtn.text = text
        positiveBtn.visibility = View.VISIBLE
        positiveBtn.setOnClickListener {
            onClick()
            dialog.dismiss()
        }
        return this
    }

    fun setNegativeButton(text: String, onClick: (() -> Unit)? = null): ResonantDialog {
        negativeBtn.text = text
        negativeBtn.visibility = View.VISIBLE
        negativeBtn.setOnClickListener {
            onClick?.invoke()
            dialog.dismiss()
        }
        return this
    }

    // Opción para que no se cierre al pulsar fuera (para diálogos críticos)
    fun setCancelable(cancelable: Boolean): ResonantDialog {
        dialog.setCancelable(cancelable)
        return this
    }

    fun show() {
        dialog.show()
    }
}