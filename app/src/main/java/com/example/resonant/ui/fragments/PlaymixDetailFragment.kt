package com.example.resonant.ui.fragments

import android.app.AlertDialog
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
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
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.databinding.FragmentPlaymixDetailBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.PlaymixSongTransitionAdapter
import com.example.resonant.ui.bottomsheets.PlaymixOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.PlaymixSongOptionsBottomSheet
import com.example.resonant.ui.bottomsheets.WaveformPreviewBottomSheet
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.PlaymixDetailViewModel
import com.example.resonant.ui.viewmodels.PlaymixDetailViewModelFactory

class PlaymixDetailFragment : BaseFragment(R.layout.fragment_playmix_detail) {

    private var _binding: FragmentPlaymixDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlaymixDetailViewModel
    private lateinit var adapter: PlaymixSongTransitionAdapter
    private var playmixId: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaymixDetailBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return

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
                                viewModel.removeSong(s.playmixSongId)
                            }
                            .setNegativeButton("Cancelar")
                            .show()
                    }
                ).show(childFragmentManager, "PlaymixSongOptions")
            }
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
                    Toast.makeText(requireContext(), "No se pudieron cargar las canciones", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "Error al cargar canciones", Toast.LENGTH_SHORT).show()
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

                if (!detail.coverUrl.isNullOrEmpty()) {
                    Glide.with(this)
                        .load(detail.coverUrl)
                        .placeholder(R.drawable.ic_playmix)
                        .error(R.drawable.ic_playmix)
                        .centerCrop()
                        .into(binding.playmixCoverImage)
                    binding.playmixCoverImage.setRenderEffect(
                        RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
                    )
                }

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
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
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
                                Toast.makeText(requireContext(), "PlayMix eliminado", Toast.LENGTH_SHORT).show()
                                findNavController().popBackStack()
                            },
                            onError = { msg ->
                                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    .setNegativeButton("Cancelar")
                    .show()
            }
        ).show(childFragmentManager, "PlaymixOptions")
    }

    private fun showRenameDialog(currentName: String) {
        val input = EditText(requireContext()).apply {
            setText(currentName)
            selectAll()
            setSingleLine()
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding / 2, padding, padding / 2)

        AlertDialog.Builder(requireContext())
            .setTitle("Editar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank()) {
                    Toast.makeText(requireContext(), "El nombre no puede estar vacío", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.renamePlaymix(
                    newName = newName,
                    onSuccess = {
                        binding.playmixName.text = newName
                        binding.playmixTitle.text = newName
                    },
                    onError = { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Cancelar", null)
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
