package com.example.spomusicapp

import android.content.ContentValues.TAG
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.RelativeLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import androidx.core.content.edit


class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager
    private lateinit var signInGoogle : RelativeLayout
    private lateinit var entryInvitationButton : RelativeLayout
    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        signInGoogle = findViewById(R.id.googleLogInButton)
        entryInvitationButton = findViewById(R.id.entryInvitationButton)

        auth = Firebase.auth
        credentialManager = CredentialManager.create(baseContext)

        signInGoogle.setOnClickListener {
            launchCredentialManager()
        }

        entryInvitationButton.setOnClickListener {
            intent = Intent(applicationContext, ActivitySongList::class.java)
            startActivity(intent)
        }

        val insetsController = window.insetsController
        insetsController?.hide(WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE)
        insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val windowInsetsController = window.insetsController
        windowInsetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())


        videoView = findViewById<VideoView>(R.id.videoViewBackground1)

        val uri = Uri.parse("android.resource://${packageName}/${R.raw.footage_login}")
        videoView.setVideoURI(uri)

        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(0f, 0f)
        }

        videoView.start()

    }

    override fun onResume() {
        super.onResume()
        videoView.start()
    }

    override fun onPause() {
        super.onPause()
        videoView.pause()
    }

    private fun launchCredentialManager() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    context = this@LoginActivity,
                    request = request
                )
                handleSignIn(result.credential)
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Couldn't retrieve user's credentials: ${e.localizedMessage}")
            }
        }
    }

    private fun handleSignIn(credential: Credential) {
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            firebaseAuthWithGoogle(googleIdTokenCredential.idToken)
        } else {
            Log.w(TAG, "Credential is not of type Google ID!")
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser

                    val name = user?.displayName
                    val photoUrl = user?.photoUrl.toString()
                    val email = user?.email.toString()

                    val prefs = getSharedPreferences("user_data", MODE_PRIVATE)
                    prefs.edit() {
                        putString("name", name)
                        putString("photoUrl", photoUrl)
                        putString("email", email)
                    }

                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Error con el login", Toast.LENGTH_SHORT).show()
                }
            }
    }




}