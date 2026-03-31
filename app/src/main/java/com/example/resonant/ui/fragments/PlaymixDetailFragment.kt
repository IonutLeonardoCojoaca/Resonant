package com.example.resonant.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        observeViewModel()

        viewModel.loadPlaymixDetail(playmixId)
    }

    private fun setupRecyclerView() {
        adapter = PlaymixSongTransitionAdapter(
            onTransitionClick = { transition -> navigateToCrossfadeEditor(transition) },
            onSongClick = { song ->
                playPlaymix(startSongId = song.songId)
            },
            onSongOptionsClick = { song ->
                AlertDialog.Builder(requireContext(), R.style.Theme_Resonant_Dialog)
                    .setTitle("Eliminar canción")
                    .setMessage("¿Eliminar \"${song.title}\" del PlayMix?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewModel.removeSong(song.playmixSongId)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        binding.songTransitionList.layoutManager = LinearLayoutManager(requireContext())
        binding.songTransitionList.adapter = adapter
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
        AlertDialog.Builder(requireContext(), R.style.Theme_Resonant_Dialog)
            .setTitle("Opciones")
            .setItems(arrayOf("Eliminar PlayMix")) { _, which ->
                when (which) {
                    0 -> {
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
                }
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
