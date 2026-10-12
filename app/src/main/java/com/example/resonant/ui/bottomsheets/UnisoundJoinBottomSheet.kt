package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.ui.activities.MainActivity
import com.example.resonant.ui.viewmodels.UnisoundViewModel
import com.example.resonant.unisound.UnisoundJoinCode
import com.example.resonant.unisound.UnisoundResult
import kotlinx.coroutines.launch

/** "Unirse con código": 6 characters, validated locally before any request. */
class UnisoundJoinBottomSheet : ResonantBottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_unisound_join, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = ViewModelProvider(requireActivity())[UnisoundViewModel::class.java]
        val input = view.findViewById<EditText>(R.id.unisoundJoinCodeInput)
        val error = view.findViewById<TextView>(R.id.unisoundJoinError)
        val join = view.findViewById<TextView>(R.id.unisoundJoinButton)

        fun setJoinEnabled(enabled: Boolean) {
            join.isEnabled = enabled
            join.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
        setJoinEnabled(false)
        input.doAfterTextChanged {
            error.visibility = View.GONE
            setJoinEnabled(UnisoundJoinCode.normalizedOrNull(it?.toString()) != null)
        }
        join.setOnClickListener {
            val code = UnisoundJoinCode.normalizedOrNull(input.text?.toString()) ?: return@setOnClickListener
            setJoinEnabled(false)
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = viewModel.joinRoom(code)) {
                    is UnisoundResult.Success -> {
                        (activity as? MainActivity)?.openUnisoundRoom()
                        dismissAllowingStateLoss()
                    }
                    is UnisoundResult.Failure -> {
                        error.text = result.message
                        error.visibility = View.VISIBLE
                        setJoinEnabled(true)
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "UnisoundJoinBottomSheet"
        private const val DISABLED_ALPHA = 0.4f
    }
}
