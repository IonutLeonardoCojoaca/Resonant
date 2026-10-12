package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.R
import com.example.resonant.data.network.UnisoundMemberDTO
import com.example.resonant.data.network.UnisoundSettingsUpdateDTO
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.UnisoundViewModel
import com.example.resonant.unisound.UnisoundResult
import com.example.resonant.unisound.UnisoundRoomState
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

/** Host-only settings: permissions, entry, code, members and ending the room. */
class UnisoundSettingsBottomSheet : ResonantBottomSheetDialogFragment() {
    private lateinit var viewModel: UnisoundViewModel
    private var rendering = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_unisound_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[UnisoundViewModel::class.java]
        val guestsAdd = view.findViewById<SwitchMaterial>(R.id.unisoundSwitchGuestsAdd)
        val guestsControl = view.findViewById<SwitchMaterial>(R.id.unisoundSwitchGuestsControl)
        val joinOpen = view.findViewById<SwitchMaterial>(R.id.unisoundSwitchJoinOpen)
        val members = view.findViewById<LinearLayout>(R.id.unisoundSettingsMembers)

        guestsAdd.setOnCheckedChangeListener { _, checked -> update(UnisoundSettingsUpdateDTO(guestsCanAdd = checked)) }
        guestsControl.setOnCheckedChangeListener { _, checked -> update(UnisoundSettingsUpdateDTO(guestsCanControl = checked)) }
        joinOpen.setOnCheckedChangeListener { _, checked -> update(UnisoundSettingsUpdateDTO(joinOpen = checked)) }
        view.findViewById<View>(R.id.unisoundSettingsRegenerate).setOnClickListener {
            perform { viewModel.regenerateCode() }
        }
        view.findViewById<View>(R.id.unisoundSettingsEnd).setOnClickListener { confirmEnd() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state == null || !state.isHost) {
                        dismissAllowingStateLoss()
                        return@collect
                    }
                    rendering = true
                    guestsAdd.isChecked = state.settings.guestsCanAdd
                    guestsControl.isChecked = state.settings.guestsCanControl
                    joinOpen.isChecked = state.settings.joinOpen
                    rendering = false
                    renderMembers(members, state)
                }
            }
        }
    }

    private fun renderMembers(container: LinearLayout, state: UnisoundRoomState) {
        container.removeAllViews()
        state.members.filter { it.userId != state.myUserId }.forEach { member ->
            val row = layoutInflater.inflate(R.layout.item_unisound_member, container, false)
            row.findViewById<TextView>(R.id.unisoundMemberName).apply {
                text = member.name
                alpha = if (member.connected) 1f else DISCONNECTED_ALPHA
            }
            row.findViewById<View>(R.id.unisoundMemberMakeHost).setOnClickListener { confirmTransfer(member) }
            row.findViewById<View>(R.id.unisoundMemberKick).setOnClickListener { confirmKick(member) }
            container.addView(row)
        }
    }

    private fun update(update: UnisoundSettingsUpdateDTO) {
        if (rendering) return
        perform { viewModel.updateSettings(update) }
    }

    private fun confirmKick(member: UnisoundMemberDTO) {
        ResonantDialog(requireContext())
            .setSection("Escuchar juntos")
            .setTitle("¿Expulsar a ${member.name}?")
            .setMessage("No podrá volver a entrar en esta sala.")
            .setPositiveButton("Expulsar") { perform { viewModel.kick(member.userId) } }
            .setDestructive()
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun confirmTransfer(member: UnisoundMemberDTO) {
        ResonantDialog(requireContext())
            .setSection("Escuchar juntos")
            .setTitle("¿Pasar el control a ${member.name}?")
            .setMessage("Dejarás de ser el anfitrión de la sala.")
            .setPositiveButton("Pasar control") { perform { viewModel.transferHost(member.userId) } }
            .setNegativeButton("Cancelar")
            .show()
    }

    private fun confirmEnd() {
        ResonantDialog(requireContext())
            .setSection("Escuchar juntos")
            .setTitle("¿Terminar la sala?")
            .setMessage("La música se detendrá para todos.")
            .setPositiveButton("Terminar") { perform { viewModel.endRoom() } }
            .setDestructive()
            .setNegativeButton("Cancelar")
            .show()
    }

    /** Runs a host action and reports its error, if any. */
    private fun perform(action: suspend () -> UnisoundResult<*>) {
        val activity = requireActivity()
        activity.lifecycleScope.launch {
            val result = action()
            if (result is UnisoundResult.Failure) {
                activity.showResonantSnackbar(result.message, R.color.errorColor, R.drawable.ic_warning)
            }
        }
    }

    companion object {
        const val TAG = "UnisoundSettingsBottomSheet"
        private const val DISCONNECTED_ALPHA = 0.5f
    }
}
