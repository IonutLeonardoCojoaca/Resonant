package com.example.spomusicapp

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SongActivity : AppCompatActivity() {

    private lateinit var blurrySongImageBackground: ImageView
    private lateinit var arrowGoBackButton: ImageButton

    private val backCallback = OnBackInvokedCallback {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_song)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        blurrySongImageBackground = findViewById(R.id.blurrySongImageBackground)
        arrowGoBackButton = findViewById(R.id.arrowGoBackButton)

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            backCallback
        )

        arrowGoBackButton.setOnClickListener {
            backCallback.onBackInvoked()
        }

        val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
        blurrySongImageBackground.setRenderEffect(blurEffect)




    }







}