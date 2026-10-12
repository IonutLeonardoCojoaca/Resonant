package com.example.resonant.ui.bottomsheets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.UnisoundViewModel
import com.example.resonant.unisound.UnisoundQrRenderer
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.unisound.UnisoundRoomState
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Invite sheet: QR of the join link, the code and the system share sheet. */
class UnisoundShareBottomSheet : ResonantBottomSheetDialogFragment() {
    private lateinit var viewModel: UnisoundViewModel
    private var renderedJoinUrl: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_unisound_share, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[UnisoundViewModel::class.java]
        val qr = view.findViewById<ImageView>(R.id.unisoundQrImage)
        val code = view.findViewById<TextView>(R.id.unisoundShareCode)
        val closed = view.findViewById<TextView>(R.id.unisoundShareClosed)
        val regenerate = view.findViewById<TextView>(R.id.unisoundRegenerateButton)

        view.findViewById<View>(R.id.unisoundShareLinkButton).setOnClickListener {
            viewModel.state.value?.joinUrl?.let(::shareLink)
        }
        regenerate.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = viewModel.regenerateCode()
                if (result is UnisoundResult.Failure) {
                    showResonantSnackbar(result.message, R.color.errorColor, R.drawable.ic_warning)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state == null) {
                        dismissAllowingStateLoss()
                        return@collect
                    }
                    code.text = state.code.orEmpty()
                    closed.visibility = if (state.settings.joinOpen) View.GONE else View.VISIBLE
                    regenerate.visibility = if (state.isHost) View.VISIBLE else View.GONE
                    renderQr(qr, state)
                }
            }
        }
    }

    private suspend fun renderQr(target: ImageView, state: UnisoundRoomState) {
        val joinUrl = state.joinUrl ?: return
        if (joinUrl == renderedJoinUrl) return
        renderedJoinUrl = joinUrl
        val size = resources.getDimensionPixelSize(R.dimen.unisound_qr_size)
        val bitmap = withContext(Dispatchers.Default) { UnisoundQrRenderer.render(joinUrl, size) }
        target.setImageBitmap(bitmap)
    }

    private fun shareLink(joinUrl: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Escucha música conmigo en Resonant: $joinUrl")
        }
        startActivity(Intent.createChooser(send, "Compartir sala"))
    }

    companion object {
        const val TAG = "UnisoundShareBottomSheet"
    }
}
