package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.data.models.Song
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.ui.adapters.PlaymixSelectorAdapter
import com.example.resonant.ui.viewmodels.PlaymixListViewModel
import com.example.resonant.ui.viewmodels.PlaymixListViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectPlaymixBottomSheet(
    private val song: Song,
    private val onNoPlaymixesFound: () -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var playmixAdapter: PlaymixSelectorAdapter
    private lateinit var playmixRecyclerView: RecyclerView
    private lateinit var noPlaymixTextView: TextView
    private lateinit var selectPlaymixText: TextView

    private val playmixListViewModel: PlaymixListViewModel by viewModels {
        val playmixManager = PlaymixManager(requireContext())
        PlaymixListViewModelFactory(playmixManager)
    }

    override fun getTheme(): Int {
        return R.style.AppBottomSheetDialogTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_playmix_selector, container, false)

        val songImage: ShapeableImageView = view.findViewById(R.id.songImage)
        val songTitle: TextView = view.findViewById(R.id.songTitle)
        val songArtist: TextView = view.findViewById(R.id.songArtist)
        val songStreams: TextView = view.findViewById(R.id.songStreams)

        noPlaymixTextView = view.findViewById(R.id.noPlaymixTextView)
        selectPlaymixText = view.findViewById(R.id.selectPlaymixText)
        playmixRecyclerView = view.findViewById(R.id.playmixList)

        songTitle.text = song.title
        songArtist.text = song.artistName ?: song.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: "Desconocido"

        if (song.streams == 0) {
            songStreams.text = "Sin reproducciones"
        } else if (song.streams == 1) {
            songStreams.text = "${song.streams} reproducción"
        } else {
            songStreams.text = "${song.streams} reproducciones"
        }

        Glide.with(songImage).load(song.coverUrl ?: song.imageFileName)
            .placeholder(R.drawable.ic_disc).into(songImage)

        setupRecyclerView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playmixListViewModel.playmixes.observe(viewLifecycleOwner) { playmixes ->
            playmixAdapter.submitList(playmixes ?: emptyList())
            updateEmptyState(playmixes.isNullOrEmpty())
        }

        playmixListViewModel.loadMyPlaymixes()
    }

    private fun setupRecyclerView() {
        val playmixManager = PlaymixManager(requireContext())
        playmixAdapter = PlaymixSelectorAdapter { selectedPlaymix ->
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        playmixManager.addSongToPlaymix(selectedPlaymix.id, song.id)
                    }
                    showResonantSnackbar(
                        text = "¡Canción añadida a '${selectedPlaymix.name}'!",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.ic_success
                    )
                    playmixListViewModel.refreshPlaymixes()
                    dismiss()
                } catch (e: Exception) {
                    Log.e("SelectPlaymixBS", "Error al añadir la canción: ${e.message}")
                    showResonantSnackbar(
                        text = "Error al añadir la canción",
                        colorRes = R.color.errorColor,
                        iconRes = R.drawable.ic_error
                    )
                    dismiss()
                }
            }
        }
        playmixRecyclerView.adapter = playmixAdapter
        playmixRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            playmixRecyclerView.visibility = View.GONE
            noPlaymixTextView.visibility = View.VISIBLE
            selectPlaymixText.visibility = View.GONE
            noPlaymixTextView.setOnClickListener {
                onNoPlaymixesFound()
                dismiss()
            }
        } else {
            playmixRecyclerView.visibility = View.VISIBLE
            noPlaymixTextView.visibility = View.GONE
            selectPlaymixText.visibility = View.VISIBLE
        }
    }
}
