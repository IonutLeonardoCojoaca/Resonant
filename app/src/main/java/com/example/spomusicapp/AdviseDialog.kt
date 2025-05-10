package com.example.spomusicapp

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import androidx.core.graphics.drawable.toDrawable

class AdviseDialog : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog?.setCanceledOnTouchOutside(false)
        return inflater.inflate(R.layout.dialog_fragment_advise, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dismissButton: Button = view.findViewById(R.id.dismissButton)
        dismissButton.setOnClickListener {
            dismiss()
        }
    }

}