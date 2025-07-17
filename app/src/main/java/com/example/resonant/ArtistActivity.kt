package com.example.resonant

import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        backgroundImage = findViewById(R.id.backgroundImage)
        arrowGoBackButton = findViewById(R.id.arrowGoBackButton)
        nestedScroll = findViewById(R.id.nested_scroll)

        backgroundImage.scaleX = 1.1f
        backgroundImage.scaleY = 1.1f
        backgroundImage.alpha = 0f

        backgroundImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000)
            .start()

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            backgroundImage.translationY = offset
        }

        arrowGoBackButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

    }


}