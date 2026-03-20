package com.example.resonant.ui.fragments

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.resonant.R

class UpdateDialogFragment : DialogFragment() {

    interface UpdateDialogListener {
        fun onUpdateConfirmed(downloadUrl: String, version: String)
        fun onUpdateDeferred()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val dialog = Dialog(ctx)

        // 🔥 CAMBIO 1: Inflamos el diseño compartido 'dialog_resonant_custom'
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_resonant_custom, null, false)

        // Recuperar argumentos
        val title = requireArguments().getString(ARG_TITLE) ?: "Actualización"
        val message = requireArguments().getString(ARG_MESSAGE) ?: "Nueva versión disponible."
        val forced = requireArguments().getBoolean(ARG_FORCED, false)
        val downloadUrl = requireArguments().getString(ARG_DOWNLOAD_URL) ?: ""
        val version = requireArguments().getString(ARG_VERSION) ?: ""

        // 🔥 CAMBIO 2: Usamos los IDs del nuevo layout compartido
        val titleView = view.findViewById<TextView>(R.id.dialogTitle)
        val messageView = view.findViewById<TextView>(R.id.dialogMessage)
        val positiveBtn = view.findViewById<TextView>(R.id.positiveButton)
        val negativeBtn = view.findViewById<TextView>(R.id.negativeButton)

        titleView.text = title
        messageView.text = message

        // Configurar Botón Aceptar (Positivo)
        positiveBtn.text = "Actualizar"
        positiveBtn.setOnClickListener {
            (activity as? UpdateDialogListener)?.onUpdateConfirmed(downloadUrl, version)
            if (!forced) dismissAllowingStateLoss()
        }

        // Lógica para Actualización Forzada
        if (forced) {
            negativeBtn.visibility = View.GONE // Ocultamos botón cancelar
            isCancelable = false
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        } else {
            negativeBtn.visibility = View.VISIBLE
            view.findViewById<View>(R.id.buttonDivider).visibility = View.VISIBLE
            negativeBtn.text = "Más tarde"
            negativeBtn.setOnClickListener {
                (activity as? UpdateDialogListener)?.onUpdateDeferred()
                dismissAllowingStateLoss()
            }
            isCancelable = true
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
        }

        // Configuración visual de la ventana
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)

        // 🔥 CAMBIO 3: Fondo transparente para que se vean los bordes redondeados
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val displayMetrics = ctx.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val marginPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, displayMetrics
        ).toInt()
        dialog.window?.setLayout(
            screenWidth - marginPx,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        return dialog
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MESSAGE = "arg_message"
        private const val ARG_FORCED = "arg_forced"
        private const val ARG_DOWNLOAD_URL = "arg_download_url"
        private const val ARG_VERSION = "arg_version"

        fun newInstance(
            title: String,
            message: String,
            forced: Boolean,
            downloadUrl: String,
            version: String
        ): UpdateDialogFragment {
            val f = UpdateDialogFragment()
            f.arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_MESSAGE, message)
                putBoolean(ARG_FORCED, forced)
                putString(ARG_DOWNLOAD_URL, downloadUrl)
                putString(ARG_VERSION, version)
            }
            return f
        }
    }
}