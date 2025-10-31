package com.example.resonant.ui.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RelativeLayout
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
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_update, null, false)

        val title = requireArguments().getString(ARG_TITLE) ?: "Actualización disponible"
        val message = requireArguments().getString(ARG_MESSAGE) ?: "Hay una nueva versión disponible."
        val forced = requireArguments().getBoolean(ARG_FORCED, false)
        val downloadUrl = requireArguments().getString(ARG_DOWNLOAD_URL) ?: ""
        val version = requireArguments().getString(ARG_VERSION) ?: ""

        view.findViewById<TextView>(R.id.adviseTitle).text = title
        view.findViewById<TextView>(R.id.adviseMessage).text = message

        val acceptBtn = view.findViewById<Button>(R.id.acceptButton)
        val cancelBtn = view.findViewById<Button>(R.id.dismissButton)

        acceptBtn.apply {
            text = "Actualizar"
            setOnClickListener {
                (activity as? UpdateDialogListener)?.onUpdateConfirmed(downloadUrl, version)
                if (!forced) dismissAllowingStateLoss()
            }
        }

        if (forced) {
            cancelBtn.visibility = View.GONE

            // Haz que "Actualizar" ocupe todo el ancho
            val lp = (acceptBtn.layoutParams as RelativeLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT, 0)
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
            }
            acceptBtn.layoutParams = lp

            isCancelable = false
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        } else {
            cancelBtn.visibility = View.VISIBLE
            cancelBtn.text = "Más tarde"
            cancelBtn.setOnClickListener {
                (activity as? UpdateDialogListener)?.onUpdateDeferred()
                dismissAllowingStateLoss()
            }

            isCancelable = true
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
        }

        dialog.setContentView(view)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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