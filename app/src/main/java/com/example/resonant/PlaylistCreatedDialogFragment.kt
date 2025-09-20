package com.example.resonant

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import androidx.fragment.app.DialogFragment

class PlaylistCreatedDialogFragment(
    private val onAccept: () -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_playlist_created, null, false)

        view.findViewById<Button>(R.id.dismissButton).setOnClickListener {
            onAccept()
            dismiss()
        }

        dialog.setContentView(view)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }
}