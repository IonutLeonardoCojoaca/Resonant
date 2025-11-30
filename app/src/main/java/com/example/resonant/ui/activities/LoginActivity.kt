package com.example.resonant.ui.activities

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.GoogleTokenDTO
import com.example.resonant.ui.activities.MainActivity
import com.example.resonant.R
import com.example.resonant.ui.viewmodels.UserViewModel
import com.example.resonant.data.models.User
import com.example.resonant.managers.UserManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var loginButton : Button
    private lateinit var videoView: VideoView
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager
    private lateinit var userViewModel: UserViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val insetsController = window.insetsController
        insetsController?.hide(WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE)
        insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val windowInsetsController = window.insetsController
        windowInsetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())

        loginButton = findViewById(R.id.loginButton)
        videoView = findViewById(R.id.videoViewBackground1)
        userViewModel = ViewModelProvider(this).get(UserViewModel::class.java)

        auth = Firebase.auth
        credentialManager = CredentialManager.Companion.create(baseContext)
        startVideo(videoView)

        loginButton.setOnClickListener {
            launchCredentialManager()
        }

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
                Log.e(ContentValues.TAG, "Couldn't retrieve user's credentials: ${e.localizedMessage}")
            }
        }
    }

    private fun handleSignIn(credential: Credential) {
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.Companion.createFrom(credential.data)
            firebaseAuthWithGoogle(googleIdTokenCredential.idToken)
        } else {
            Log.w("Credential", "Credential is not of type Google ID!")
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val request = GoogleTokenDTO(idToken)
                    val user = auth.currentUser
                    val email = user?.email.toString()
                    lifecycleScope.launch {
                        try {
                            val authService = ApiClient.getAuthService(applicationContext)
                            val userService = ApiClient.getUserService(applicationContext)

                            val response = authService.loginWithGoogle(request)

                            saveTokens(response.accessToken, response.refreshToken, email)

                            val userData = userService.getUserByEmail(email)
                            userViewModel.user = userData

                            if (userData.isBanned == true) {
                                Toast.makeText(this@LoginActivity, "Tu cuenta tiene acceso restringido.", Toast.LENGTH_LONG).show()
                                FirebaseAuth.getInstance().signOut()
                                userViewModel.user = null

                                // Limpieza de preferencias
                                getSharedPreferences("user_data", MODE_PRIVATE).edit().clear().apply()
                                getSharedPreferences("Auth", MODE_PRIVATE).edit().clear().apply()
                                return@launch
                            }

                            // 4. Guardamos datos (puedes usar tu función local o el UserManager)
                            saveUserData(userData)

                            // OPCIONAL: Si quieres usar el UserManager para asegurar que el ID queda grabado
                            val userManager = UserManager(applicationContext)
                            userManager.saveUserId(userData.id)

                            Log.d("LOGIN", "Usuario id guardado: ${userData.id}")
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        } catch (e: Exception) {
                            Toast.makeText(this@LoginActivity, "Error backend: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Error con Firebase login", Toast.LENGTH_SHORT).show()
                }
            }
    }

    fun saveUserData(userData: User) {
        val prefs = getSharedPreferences("user_data", MODE_PRIVATE)
        prefs.edit().apply {
            putString("NAME", userData.name)
            putString("EMAIL", userData.email)
            putString("USER_ID", userData.id)
            putBoolean("IS_BANNED", userData.isBanned)
            apply()
        }
    }

    fun saveTokens(accessToken: String, refreshToken: String, email: String) {
        val prefs = getSharedPreferences("Auth", MODE_PRIVATE)
        prefs.edit().apply {
            putString("ACCESS_TOKEN", accessToken)
            putString("REFRESH_TOKEN", refreshToken)
            putString("EMAIL", email)
            apply()
        }
        Log.d("LOGIN", "Token guardado: ${refreshToken}")
    }

    fun startVideo (videoView: VideoView){
        val uri = Uri.parse("android.resource://${packageName}/${R.raw.fondo_resonant}")
        videoView.setVideoURI(uri)

        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            mediaPlayer.setVolume(0f, 0f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val playbackParams = mediaPlayer.playbackParams
                playbackParams.speed = 0.5f
                mediaPlayer.playbackParams = playbackParams
            }
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

}