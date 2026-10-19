package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import com.example.resonant.ui.bottomsheets.CopyTransitionBottomSheet
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.PlaymixMixMode
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlin.math.roundToInt
import com.example.resonant.databinding.FragmentPlaymixDetailBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.PlaymixSongTransitionAdapter
import com.example.resonant.ui.bottomsheets.PlaymixOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.PlaymixSongOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.WaveformPreviewBottomSheet
import com.example.resonant.ui.dialogs.PlaymixDialog
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.PlaymixDetailViewModel
import com.example.resonant.ui.viewmodels.PlaymixDetailViewModelFactory
import com.example.resonant.ui.components.DefaultCover
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

class PlaymixDetailFragment : BaseFragment(R.layout.fragment_playmix_detail) {

    private var _binding: FragmentPlaymixDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlaymixDetailViewModel
    private lateinit var adapter: PlaymixSongTransitionAdapter
    private var playmixId: String = ""
    private var isReadOnly: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaymixDetailBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return
        isReadOnly = arguments?.getBoolean("isReadOnly", false) ?: false

        val playmixManager = PlaymixManager(requireContext())
        viewModel = ViewModelProvider(this, PlaymixDetailViewModelFactory(playmixManager))
            .get(PlaymixDetailViewModel::class.java)

        setupRecyclerView()
        setupListeners()
        setupParallaxTitle()
        observeViewModel()

        viewModel.loadPlaymixDetail(playmixId)
    }

    private fun setupRecyclerView() {
        adapter = PlaymixSongTransitionAdapter(
            onTransitionClick = { transition -> navigateToCrossfadeEditor(transition) },
            onAddTransitionClick = { transition -> navigateToCrossfadeEditor(transition) },
            onPreviewClick = { transition ->
                WaveformPreviewBottomSheet(playmixId, transition)
                    .show(childFragmentManager, "WaveformPreview")
            },
            onCopyTransitionClick = { transition ->
                showCopyTransitionDialog(transition)
            },
            onSongClick = { song ->
                playPlaymix(startSongId = song.songId)
            },
            onSongOptionsClick = { song ->
                PlaymixSongOptionsBottomSheet(
                    song = song,
                    onPlayClick = { s -> playPlaymix(startSongId = s.songId) },
                    onSeeSongClick = { s ->
                        val bundle = Bundle().apply { putString("songId", s.songId) }
                        findNavController().navigate(R.id.songFragment, bundle)
                    },
                    onRemoveClick = { s ->
                        ResonantDialog(requireContext())
                            .setSection("PlayMix")
                            .setTitle("Eliminar canción")
                            .setMessage("¿Eliminar \"${s.title ?: "esta canción"}\" del PlayMix?")
                            .setDestructive()
                            .setPositiveButton("Eliminar") {
                                viewModel.removeSong(s.playmixSongId) {
                                    showResonantSnackbar(
                                        text = s.title ?: "Canción eliminada",
                                        type = SnackType.SUCCESS,
                                        title = "Eliminada de la PlayMix"
                                    )
                                }
                            }
                            .setNegativeButton("Cancelar")
                            .show()
                    }
                ).show(childFragmentManager, "PlaymixSongOptions")
            },
            isReadOnly = isReadOnly
        )
        binding.songTransitionList.layoutManager = LinearLayoutManager(requireContext())
        binding.songTransitionList.adapter = adapter
    }

    private fun setupParallaxTitle() {
        binding.appBarLayout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, offset ->
            val ratio = abs(offset).toFloat() / appBar.totalScrollRange.toFloat()
            binding.playmixTitle.alpha = if (ratio > 0.7f) (ratio - 0.7f) / 0.3f else 0f
        })
    }

    private fun setupListeners() {
        binding.arrowGoBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.settingsBackground.setOnClickListener {
            showSettingsMenu()
        }
        binding.settingsBackground.visibility = if (isReadOnly) View.GONE else View.VISIBLE

        binding.playButton.setOnClickListener {
            playPlaymix()
        }
    }

    private fun playPlaymix(startSongId: String? = null) {
        val b = _binding ?: return
        val songManager = SongManager(requireContext())
        b.playButton.isEnabled = false
        b.playButton.alpha = 0.5f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = viewModel.fetchSongsForPlayback(songManager, startSongId)
                val b2 = _binding ?: return@launch
                b2.playButton.isEnabled = true
                b2.playButton.alpha = 1f

                if (result == null) {
                    showResonantSnackbar("No se pudieron cargar las canciones", SnackType.ERROR)
                    return@launch
                }

                val (songs, index) = result
                val songList = ArrayList(songs)
                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PLAY
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, songList[index])
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, index)
                    putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.PLAYMIX)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, playmixId)
                }
                requireContext().startService(playIntent)
            } catch (e: Exception) {
                val b2 = _binding ?: return@launch
                b2.playButton.isEnabled = true
                b2.playButton.alpha = 1f
                showResonantSnackbar("Error al cargar las canciones de la PlayMix", SnackType.ERROR)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.screenState.observe(viewLifecycleOwner) { state ->
            if (state.isLoading) {
                binding.lottieLoader.visibility = View.VISIBLE
                binding.lottieLoader.playAnimation()
            } else {
                binding.lottieLoader.visibility = View.GONE
                binding.lottieLoader.cancelAnimation()
            }

            state.detail?.let { detail ->
                binding.playmixTitle.text = detail.name
                binding.playmixName.text = detail.name

                if (!detail.description.isNullOrEmpty()) {
                    binding.playmixDescription.text = detail.description
                    binding.playmixDescription.visibility = View.VISIBLE
                } else {
                    binding.playmixDescription.visibility = View.GONE
                }

                val count = detail.numberOfTracks
                val songText = if (count == 1) "1 canción" else "$count canciones"
                binding.playmixNumberOfTracks.text = songText
                binding.playmixDuration.text = formatDuration(detail.duration)

                // Sin canciones no hay portada: la tarjeta por defecto, desenfocada igual que una portada.
                DefaultCover.load(binding.playmixCoverImage, detail.coverUrl, DefaultCover.Kind.PLAYMIX, detail.name)
                binding.playmixCoverImage.setRenderEffect(
                    RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
                )

                bindSetOverview(detail)

                if (detail.songs.isEmpty()) {
                    binding.noSongsText.visibility = View.VISIBLE
                    binding.songTransitionList.visibility = View.GONE
                } else {
                    binding.noSongsText.visibility = View.GONE
                    binding.songTransitionList.visibility = View.VISIBLE
                    adapter.submitItems(detail.songs, detail.transitions)
                }
            }

            state.error?.let {
                showResonantSnackbar(it, SnackType.ERROR)
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                showResonantSnackbar(it, SnackType.ERROR)
            }
        }
    }

    /** Curva de tempo del set y resumen de cómo se mezclan sus parejas (automático / avanzada). */
    private fun bindSetOverview(detail: PlaymixDetailDTO) {
        val songs = detail.songs.sortedBy { it.position }
        val tempos = songs.map { song -> song.audioAnalysis?.bpm?.takeIf { it > 0 } }
        val known = tempos.filterNotNull()
        binding.tempoFlowSection.isVisible = known.size >= 2
        if (known.size >= 2) {
            binding.tempoFlowRange.text = "${known.min().roundToInt()}–${known.max().roundToInt()} BPM"
            binding.tempoFlowView.setColors(
                ContextCompat.getColor(requireContext(), R.color.playmix_accent),
                ContextCompat.getColor(requireContext(), R.color.playmix_accent_end)
            )
            binding.tempoFlowView.setTempos(tempos, animate = true)
        }
        binding.setlistLabel.isVisible = songs.isNotEmpty()

        val pairs = songs.zipWithNext()
        val automatic = pairs.count { (from, to) ->
            PlaymixMixMode.isSmart(detail.transitions.find {
                it.fromPlaymixSongId == from.playmixSongId && it.toPlaymixSongId == to.playmixSongId
            })
        }
        val advanced = pairs.size - automatic
        binding.playmixBlendSummary.isVisible = pairs.isNotEmpty()
        binding.playmixBlendSummary.text = when {
            advanced == 0 -> "Blend automático"
            automatic == 0 -> if (advanced == 1) "1 transición avanzada" else "$advanced transiciones avanzadas"
            else -> "$advanced avanzadas · $automatic auto"
        }
    }

    private fun showSettingsMenu() {
        val detail = viewModel.screenState.value?.detail ?: return
        PlaymixOptionsBottomSheet(
            detail = detail,
            onPlayClick = { playPlaymix() },
            onEditNameClick = { showRenameDialog(detail.name) },
            onDeleteClick = {
                ResonantDialog(requireContext())
                    .setSection("PlayMix")
                    .setTitle("Eliminar PlayMix")
                    .setMessage("¿Estás seguro de que quieres eliminar \"${detail.name}\"? Esta acción no se puede deshacer.")
                    .setDestructive()
                    .setPositiveButton("Eliminar") {
                        viewModel.deletePlaymix(
                            onSuccess = {
                                showResonantSnackbar(detail.name, SnackType.SUCCESS, title = "PlayMix eliminada")
                                findNavController().popBackStack()
                            },
                            onError = { msg ->
                                showResonantSnackbar(msg, SnackType.ERROR, title = "No se pudo eliminar la PlayMix")
                            }
                        )
                    }
                    .setNegativeButton("Cancelar")
                    .show()
            }
        ).show(childFragmentManager, "PlaymixOptions")
    }

    private fun showRenameDialog(currentName: String) {
        PlaymixDialog(requireContext())
            .setIcon(R.drawable.ic_edit)
            .setEyebrow("PlayMix")
            .setTitle("Editar nombre")
            // Mismo límite que al crearla (CreatePlaymixFragment.MAX_NAME).
            .setInput(currentName, hint = "Nombre de la PlayMix", maxLength = PLAYMIX_NAME_MAX) { name ->
                if (name.isBlank()) "El nombre no puede estar vacío" else null
            }
            .setPrimary("Guardar") { newName ->
                viewModel.renamePlaymix(
                    newName = newName,
                    onSuccess = {
                        // La respuesta puede llegar con la vista ya destruida.
                        _binding?.let {
                            it.playmixName.text = newName
                            it.playmixTitle.text = newName
                        }
                        showResonantSnackbar(newName, SnackType.SUCCESS, title = "Nombre actualizado")
                    },
                    onError = { msg ->
                        showResonantSnackbar(msg, SnackType.ERROR, title = "No se pudo cambiar el nombre")
                    }
                )
            }
            .setTertiary("Cancelar")
            .show()
    }

    private fun navigateToCrossfadeEditor(transition: PlaymixTransitionDTO) {
        val bundle = Bundle().apply {
            putString("playmixId", playmixId)
            putString("transitionId", transition.id)
        }
        findNavController().navigate(R.id.action_playmixDetailFragment_to_crossfadeEditorFragment, bundle)
    }

    private fun showCopyTransitionDialog(transition: PlaymixTransitionDTO) {
        val songs = viewModel.screenState.value?.detail?.songs
        val songA = songs?.find { it.playmixSongId == transition.fromPlaymixSongId }?.title ?: "Canción A"
        val songB = songs?.find { it.playmixSongId == transition.toPlaymixSongId }?.title ?: "Canción B"
        CopyTransitionBottomSheet(transition, playmixId, songA, songB)
            .show(childFragmentManager, "CopyTransition")
    }

    private fun formatDuration(seconds: Int): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%dh %02dmin", hrs, mins)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Largo máximo del nombre de una PlayMix: el mismo que al crearla (`CreatePlaymixFragment.MAX_NAME`). */
private const val PLAYMIX_NAME_MAX = 30
