package com.example.spomusicapp

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.firebase.firestore.FirebaseFirestore
import com.squareup.picasso.Picasso

class ArtistActivity : AppCompatActivity() {

    private lateinit var backgroundImage: ImageView
    private lateinit var arrowGoBackButton: ImageButton
    private lateinit var nestedScroll: NestedScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_artist)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        backgroundImage = findViewById(R.id.backgroundImage)
        arrowGoBackButton = findViewById(R.id.arrowGoBackButton)
        nestedScroll = findViewById(R.id.nested_scroll)

        val initialTranslationY = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            -150f,
            resources.displayMetrics
        )

        backgroundImage.scaleX = 1.1f
        backgroundImage.scaleY = 1.1f
        backgroundImage.alpha = 0f

        backgroundImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .start()

        backgroundImage.translationY = initialTranslationY

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            backgroundImage.translationY = initialTranslationY + offset
        }

        val artistId = intent.getStringExtra(PreferenceKeys.CURRENT_ARTIST_ID)

        if (artistId != null) {
            loadArtistById(artistId) { artist ->
                if (artist != null) {
                    findViewById<TextView>(R.id.artistName).text = artist.name
                    Picasso.get()
                        .load(artist.urlPhoto)
                        .placeholder(R.drawable.item_shimmer_background)
                        .error(R.drawable.item_shimmer_background)
                        .into(backgroundImage)

                } else {
                    Toast.makeText(this, "Artista no encontrado", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } else {
            Toast.makeText(this, "ID del artista no proporcionado", Toast.LENGTH_SHORT).show()
            finish()
        }

        arrowGoBackButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

    }

    private fun loadArtistById(artistId: String, onResult: (Artist?) -> Unit) {
        val firestore = FirebaseFirestore.getInstance()
        val artistRef = firestore.collection("artist").document(artistId)

        artistRef.get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val artist = document.toObject(Artist::class.java)
                    onResult(artist)
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener { exception ->
                println("Error al obtener artista: ${exception.message}")
                onResult(null)
            }
    }


}