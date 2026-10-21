package com.example.resonant.ui.fragments

import android.os.Bundle
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixVisibility
import com.example.resonant.data.network.effectiveVisibility
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.PlaymixSharingManager
import com.example.resonant.ui.bottomsheets.PlaymixSheetSharing
import com.example.resonant.ui.bottomsheets.PlaymixSortBottomSheet
import com.example.resonant.ui.dialogs.PlaymixDialog
import com.example.resonant.ui.viewmodels.PlaymixLikeState
import com.example.resonant.ui.viewmodels.PlaymixSharingViewModel
import com.example.resonant.utils.ShareUtils
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

/**
 * Compartir y descubrir en el detalle de una PlayMix (playmix-sharing-discovery): cabecera "de <dueño>",
 * insignia de visibilidad, me gusta, compartir (con paso a "Con enlace" si es privada), duplicar o copiar,
 * ordenar y fusionar. Con la capacidad `playmixSharing` apagada no muestra nada y la pantalla queda como antes.
 */
class PlaymixDetailSharing(
    private val fragment: Fragment,
    private val viewModel: PlaymixSharingViewModel,
    private val currentDetail: () -> PlaymixDetailDTO?,
    private val reload: () -> Unit
) {
    private val context get() = fragment.requireContext()

    val enabled: Boolean get() = CapabilitiesManager.isPlaymixSharingEnabled()

    /** Cabecera y botones para el detalle cargado. */
    fun bindHeader(
        detail: PlaymixDetailDTO,
        isOwner: Boolean,
        ownerText: TextView,
        socialRow: android.view.View,
        visibilityChip: TextView,
        likeButton: TextView,
        shareButton: TextView
    ) {
        ownerText.isVisible = !isOwner && !detail.ownerName.isNullOrBlank()
        ownerText.text = "de ${detail.ownerName.orEmpty()}"
        socialRow.isVisible = enabled
        if (!enabled) return

        visibilityChip.isVisible = isOwner
        visibilityChip.text = PlaymixSheetSharing.label(detail.effectiveVisibility)
        visibilityChip.setOnClickListener { chooseVisibility(detail.id, detail.effectiveVisibility) }

        likeButton.isVisible = !isOwner
        viewModel.bind(detail)
        likeButton.setOnClickListener { viewModel.toggleLike(detail.id) }

        shareButton.setOnClickListener { sharePlaymix() }
    }

    fun bindLike(state: PlaymixLikeState?, likeButton: TextView) {
        state ?: return
        likeButton.text = state.count.toString()
        val icon = ContextCompat.getDrawable(context, if (state.liked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            ?.mutate()
            ?.also {
                val size = (14 * context.resources.displayMetrics.density).toInt()
                it.setBounds(0, 0, size, size)
            }
        likeButton.setCompoundDrawablesRelative(icon, null, null, null)
        likeButton.isEnabled = !state.pending
    }

    /** Filas de la hoja de opciones; null con la capacidad apagada. */
    fun sheetSharing(detail: PlaymixDetailDTO, isOwner: Boolean): PlaymixSheetSharing? {
        if (!enabled) return null
        return PlaymixSheetSharing(
            isOwner = isOwner,
            visibility = detail.effectiveVisibility,
            isLiked = viewModel.like.value?.liked ?: detail.isLiked,
            onLikeClick = { viewModel.toggleLike(detail.id) },
            onShareClick = { sharePlaymix() },
            onVisibilityClick = { chooseVisibility(detail.id, detail.effectiveVisibility) },
            onDuplicateClick = { copyToLibrary(detail, isOwner) },
            onSortClick = { openSort(detail.id) },
            onMergeClick = if (isOwner) ({ chooseMerge(detail) }) else null
        )
    }

    // ─── Compartir ───

    /** Una privada no se comparte: se ofrece pasarla a "Con enlace" o "Pública" y después se comparte. */
    fun sharePlaymix() {
        val detail = currentDetail() ?: return
        withShareableVisibility(detail) { shareable ->
            ShareUtils.shareText(
                context,
                ShareUtils.buildPlaymixShareText(shareable.id, shareable.name, shareable.numberOfTracks),
                "Compartir PlayMix"
            )
        }
    }

    fun shareTransition(transition: PlaymixTransitionDTO) {
        val detail = currentDetail() ?: return
        val songA = detail.songs.find { it.playmixSongId == transition.fromPlaymixSongId }?.title ?: "Canción A"
        val songB = detail.songs.find { it.playmixSongId == transition.toPlaymixSongId }?.title ?: "Canción B"
        withShareableVisibility(detail) { shareable ->
            ShareUtils.shareText(
                context,
                ShareUtils.buildTransitionShareText(shareable.id, transition.id, songA, songB),
                "Compartir transición"
            )
        }
    }

    private fun withShareableVisibility(detail: PlaymixDetailDTO, share: (PlaymixDetailDTO) -> Unit) {
        val isOwner = detail.isOwner ?: true
        if (!enabled || !isOwner || detail.effectiveVisibility != PlaymixVisibility.PRIVATE) {
            share(detail)
            return
        }
        PlaymixDialog(context)
            .setIcon(R.drawable.ic_share)
            .setEyebrow("Compartir")
            .setTitle("Esta PlayMix es privada")
            .setMessage("Para compartirla, elige quién podrá verla.")
            .setChoices(
                listOf(
                    PlaymixDialog.Choice("Con enlace", "Solo quien tenga el enlace"),
                    PlaymixDialog.Choice("Pública", "Aparece en búsquedas")
                )
            ) { which ->
                val visibility = if (which == 0) PlaymixVisibility.UNLISTED else PlaymixVisibility.PUBLIC
                viewModel.setVisibility(detail.id, visibility) { updated ->
                    reload()
                    share(updated)
                }
            }
            .setTertiary("Cancelar")
            .show()
    }

    // ─── Visibilidad ───

    fun chooseVisibility(playmixId: String, current: String) {
        val values = arrayOf(PlaymixVisibility.PRIVATE, PlaymixVisibility.UNLISTED, PlaymixVisibility.PUBLIC)
        val choices = listOf(
            PlaymixDialog.Choice("Privada", "Solo tú"),
            PlaymixDialog.Choice("Con enlace", "Quien tenga el enlace"),
            PlaymixDialog.Choice("Pública", "Aparece en búsquedas")
        )
        PlaymixDialog(context)
            .setIcon(R.drawable.ic_public)
            .setEyebrow("PlayMix")
            .setTitle("Visibilidad")
            .setChoices(choices, values.indexOf(current).coerceAtLeast(0)) { which ->
                if (values[which] == current) return@setChoices
                viewModel.setVisibility(playmixId, values[which]) {
                    fragment.showResonantSnackbar("Visibilidad: ${PlaymixSheetSharing.label(values[which])}", SnackType.SUCCESS)
                    reload()
                }
            }
            .setTertiary("Cancelar")
            .show()
    }

    // ─── Duplicar / copiar ───

    fun copyToLibrary(detail: PlaymixDetailDTO, isOwner: Boolean) {
        PlaymixDialog(context)
            .setIcon(R.drawable.ic_unisound_copy)
            .setEyebrow("PlayMix")
            .setTitle(if (isOwner) "Duplicar PlayMix" else "Copiar a mi biblioteca")
            // Vacío vale (el servidor pone el nombre por defecto), y la sugerencia sin tocar también.
            .setInput("${detail.name} (copia)".take(MAX_NAME), "Nombre de la copia", MAX_NAME, requireChange = false) { null }
            .setPrimary(if (isOwner) "Duplicar" else "Copiar") { name ->
                viewModel.copy(detail.id, name.ifBlank { null }) { copy ->
                    fragment.showResonantSnackbar("PlayMix copiada a tu biblioteca", SnackType.SUCCESS)
                    openPlaymix(copy.id)
                }
            }
            .setTertiary("Cancelar")
            .show()
    }

    // ─── Ordenar ───

    fun openSort(playmixId: String) {
        PlaymixSortBottomSheet.newInstance(playmixId).apply { onApplied = { reload() } }
            .show(fragment.childFragmentManager, "PlaymixSort")
    }

    // ─── Fusionar ───

    /** Multiselección entre las propias y las que me gustan (sin la actual), nombre y fusión. */
    fun chooseMerge(detail: PlaymixDetailDTO) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val own = runCatching { PlaymixManager(context).getMyPlaymixes() }.getOrDefault(emptyList())
            val liked = runCatching { PlaymixSharingManager(context).getLikedPlaymixes(null, 50).items.orEmpty() }
                .getOrDefault(emptyList())
            val candidates: List<PlaymixDTO> = (own + liked).distinctBy { it.id }.filter { it.id != detail.id }
            if (!fragment.isAdded) return@launch
            if (candidates.isEmpty()) {
                fragment.showResonantSnackbar("No tienes otras PlayMix para fusionar", SnackType.INFO)
                return@launch
            }
            val choices = candidates.map { p ->
                val owner = if (p.isOwner == false && !p.ownerName.isNullOrBlank()) " · de ${p.ownerName}" else ""
                PlaymixDialog.Choice(p.name, "${p.numberOfTracks} canciones$owner")
            }
            val mergeDialog = PlaymixDialog(context)
            mergeDialog
                .setIcon(R.drawable.ic_playmix)
                .setEyebrow("Fusionar")
                .setTitle("Fusionar \"${detail.name}\" con…")
                .setMultiChoices(choices) { checked ->
                    when {
                        checked.isEmpty() -> "Elige al menos una PlayMix"
                        checked.size > MAX_MERGE - 1 -> "Como máximo ${MAX_MERGE - 1} PlayMix"
                        else -> null
                    }
                }
                .setPrimary("Siguiente") {
                    val selected = mergeDialog.checkedChoices().sorted().map { candidates[it].id }
                    askMergeName(listOf(detail.id) + selected, "${detail.name} + ${selected.size}")
                }
                .setTertiary("Cancelar")
                .show()
        }
    }

    private fun askMergeName(ids: List<String>, suggested: String) {
        PlaymixDialog(context)
            .setIcon(R.drawable.ic_playmix)
            .setEyebrow("Fusionar")
            .setTitle("Nombre de la nueva PlayMix")
            .setInput(suggested.take(MAX_NAME), "Nombre de la PlayMix", MAX_NAME, requireChange = false) { name ->
                if (name.isBlank()) "El nombre no puede estar vacío" else null
            }
            .setPrimary("Fusionar") { name ->
                viewModel.merge(ids, name) { merged ->
                    fragment.showResonantSnackbar("PlayMix fusionada", SnackType.SUCCESS)
                    openPlaymix(merged.id)
                }
            }
            .setTertiary("Cancelar")
            .show()
    }

    // ─── Utilidades ───

    private fun openPlaymix(playmixId: String) {
        if (!fragment.isAdded) return
        fragment.findNavController().navigate(
            R.id.action_global_to_playmixDetailFragment,
            Bundle().apply { putString("playmixId", playmixId) }
        )
    }

    private companion object {
        const val MAX_NAME = 120
        const val MAX_MERGE = 5
    }
}
