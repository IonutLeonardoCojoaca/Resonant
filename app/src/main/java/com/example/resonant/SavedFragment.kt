package com.example.resonant

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton

class SavedFragment : Fragment() {
    private lateinit var songsButton: MaterialButton
    private lateinit var artistsButton: MaterialButton
    private lateinit var albumsButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_saved, container, false)

        songsButton = view.findViewById(R.id.songsButton)
        artistsButton = view.findViewById(R.id.artistsButton)
        albumsButton = view.findViewById(R.id.albumsButton)

        songsButton.setOnClickListener {
            // Navega después de la animación
            songsButton.postDelayed({
                val action = SavedFragmentDirections.actionSavedFragmentToFavoriteSongsFragment()
                findNavController().navigate(action)
            }, 200) // El delay debe ser igual o ligeramente menor que la duración de la animación
        }

        return view
    }
}