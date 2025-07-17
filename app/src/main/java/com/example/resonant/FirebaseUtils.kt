package com.example.resonant

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseUtils {

    fun toggleLike(song: Song, onResult: (isLiked: Boolean) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val likeRef = FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("likes")
            .document(song.id)

        likeRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                likeRef.delete().addOnSuccessListener {
                    onResult(false)
                }
            } else {
                val like = hashMapOf(
                    "songId" to song.id,
                    "likedAt" to FieldValue.serverTimestamp()
                )
                likeRef.set(like).addOnSuccessListener {
                    onResult(true)
                }
            }
        }
    }

    fun loadUserLikes(onLoaded: (Set<String>) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("likes")
            .get()
            .addOnSuccessListener { result ->
                val likedSongIds = result.documents.map { it.id }.toSet()
                onLoaded(likedSongIds)
            }
    }

}