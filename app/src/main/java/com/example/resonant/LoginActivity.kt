package com.example.resonant

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.Toast
import android.widget.VideoView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.credentials.Credential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import androidx.credentials.CredentialManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var loginButton : Button
    private lateinit var videoView: VideoView
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkSessionAndNavigate(this)
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

        auth = Firebase.auth
        credentialManager = CredentialManager.create(baseContext)
        startVideo(videoView)


        loginButton.setOnClickListener {
            launchCredentialManager()
        }

    }

    fun checkSessionAndNavigate(context: Context) {
        val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)
        val accessToken = prefs.getString("ACCESS_TOKEN", null)
        val refreshToken = prefs.getString("REFRESH_TOKEN", null)
        val email = prefs.getString("EMAIL", null)

        if (accessToken != null && isTokenValid(accessToken)) {
            goToMain(context)
        } else if (refreshToken != null && email != null) {
            refreshAccessToken(refreshToken, email, context)
        } else {
            goToLogin(context)
        }
    }

    fun isTokenValid(token: String): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return false
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE))
            val json = JSONObject(payload)
            val exp = json.getLong("exp")
            val now = System.currentTimeMillis() / 1000
            exp > now
        } catch (e: Exception) {
            false
        }
    }

    fun refreshAccessToken(refreshToken: String, email: String, context: Context) {
        val service = ApiClient.getService(context)
        val call = service.refreshToken(RefreshTokenDTO(refreshToken, email))

        call.enqueue(object : Callback<AuthResponse> {
            override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
                if (response.isSuccessful) {
                    val auth = response.body()
                    auth?.let {
                        val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("ACCESS_TOKEN", it.accessToken)
                            .putString("REFRESH_TOKEN", it.refreshToken)
                            .apply()
                        goToMain(context)
                    } ?: run {
                        goToLogin(context)
                    }
                } else {
                    goToLogin(context)
                }
            }

            override fun onFailure(call: Call<AuthResponse>, t: Throwable) {
                goToLogin(context)
            }
        })
    }

    fun goToMain(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
        context.startActivity(intent)
        if (context is Activity) context.finish()
    }

    fun goToLogin(context: Context) {
        val intent = Intent(context, LoginActivity::class.java)
        context.startActivity(intent)
        if (context is Activity) context.finish()
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
                            val service = ApiClient.getService(applicationContext)
                            val response = service.loginWithGoogle(request)
                            saveTokens(response.accessToken, response.refreshToken, email)
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

    fun saveTokens(accessToken: String, refreshToken: String, email: String) {
        val prefs = getSharedPreferences("Auth", Context.MODE_PRIVATE)
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