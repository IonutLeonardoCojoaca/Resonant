package com.example.resonant

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore

class SavedFragment : Fragment() {

    private lateinit var favoriteButtonContainer: RelativeLayout
    private lateinit var favoriteSongsNumberTextView: TextView
    private var favoriteSongsNumber: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        var view = inflater.inflate(R.layout.fragment_saved, container, false)

        favoriteButtonContainer = view.findViewById(R.id.favoriteButtonContainer)
        favoriteSongsNumberTextView = view.findViewById(R.id.totalLikedSongsText)

        /*

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        observeNumberOfLikedSongs(userId) { count ->
            favoriteSongsNumberTextView.text = "Tienes $count canciones guardadas."
        }
        favoriteButtonContainer.setOnClickListener {
            val action = SavedFragmentDirections.actionSavedFragmentToFavoriteSongsFragment()
            findNavController().navigate(
                action,
                NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setPopUpTo(R.id.savedFragment, true)
                    .build()
            )
        }

        */

        return view
    }

    fun observeNumberOfLikedSongs(userId: String, onNumberChanged: (Int) -> Unit) {
        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(userId)
            .collection("likes")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("LikedSongsCount", "Error al escuchar likes: ${error.message}")
                    onNumberChanged(0)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val count = snapshot.size()
                    onNumberChanged(count)
                } else {
                    onNumberChanged(0)
                }
            }
    }




}