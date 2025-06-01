package com.example.spomusicapp

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class SavedFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        var view = inflater.inflate(R.layout.fragment_saved, container, false)




        return view
    }

    private fun loadLikedSongs(callback: (List<Song>) -> Unit) {
        val firestore = FirebaseFirestore.getInstance()
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser == null) {
            callback(emptyList())
            return
        }

        val likesRef = firestore.collection("users")
            .document(currentUser.uid)
            .collection("likes")

        likesRef.get()
            .addOnSuccessListener { likeDocuments ->
                val likedSongIds = likeDocuments.mapNotNull { it.id }

                if (likedSongIds.isEmpty()) {
                    callback(emptyList())
                    return@addOnSuccessListener
                }

                // Ahora consulta Firestore para obtener esas canciones
                firestore.collection("songs")
                    .whereIn(FieldPath.documentId(), likedSongIds.take(10)) // Firestore tiene límite de 10 elementos
                    .get()
                    .addOnSuccessListener { songDocuments ->
                        val likedSongs = songDocuments.mapNotNull { it.toObject(Song::class.java) }
                        callback(likedSongs)
                    }
                    .addOnFailureListener {
                        Log.e("SongRepository", "Error cargando canciones favoritas: ${it.message}")
                        callback(emptyList())
                    }
            }
            .addOnFailureListener {
                Log.e("SongRepository", "Error cargando likes del usuario: ${it.message}")
                callback(emptyList())
            }
    }

/*
    private fun reloadLikedSongs() {
        showShimmer(true)
        recyclerViewSongs.visibility = View.GONE

        loadLikedSongs { likedSongs ->
            if (likedSongs.isNotEmpty()) {
                val enrichedSongsDeferred = likedSongs.map { song ->
                    lifecycleScope.async(Dispatchers.IO) {
                        Utils.enrichSong(requireContext(), song)
                    }
                }

                lifecycleScope.launch {
                    val enrichedSongs = enrichedSongsDeferred.awaitAll().filterNotNull()

                    songList.clear()
                    songList.addAll(enrichedSongs)

                    val safeCopy = songList.toList()
                    preloadSongsInBackground(requireContext(), safeCopy)

                    val shimmerStart = System.currentTimeMillis()

                    songAdapter.submitList(songList.toList()) {
                        val elapsed = System.currentTimeMillis() - shimmerStart
                        val remaining = (1200 - elapsed).coerceAtLeast(0)

                        recyclerViewSongs.visibility = View.INVISIBLE

                        lifecycleScope.launch {
                            delay(remaining)
                            hideShimmer()
                            val controller = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation_fade_slide)
                            recyclerViewSongs.layoutAnimation = controller
                            recyclerViewSongs.scheduleLayoutAnimation()
                            recyclerViewSongs.visibility = View.VISIBLE
                        }
                    }

                    PlaybackManager.updateSongs(songList)

                    // Guardar en caché
                    val sharedPreferences = requireContext().getSharedPreferences("song_cache", MODE_PRIVATE)
                    sharedPreferences.edit {
                        val json = Gson().toJson(songList)
                        putString("cached_songs", json)
                    }

                    SongCache.cachedSongs = songList.toList()
                    hasMoreItems = false
                }
            } else {
                Toast.makeText(requireContext(), "No tienes canciones marcadas como favoritas", Toast.LENGTH_SHORT).show()
                hideShimmer()
                recyclerViewSongs.visibility = View.VISIBLE
            }
        }
    }
    */


}