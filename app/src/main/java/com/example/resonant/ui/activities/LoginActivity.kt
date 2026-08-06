package com.example.resonant.ui.activities

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.data.models.User
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.GoogleTokenDTO
import com.example.resonant.managers.UserManager
import com.example.resonant.ui.viewmodels.UserViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var loginButton: MaterialButton
    private lateinit var loginProgress: CircularProgressIndicator
    private lateinit var loginStatus: TextView
    private lateinit var atmosphereBadge: View
    private lateinit var loginHero: View
    private lateinit var loginCard: MaterialCardView

    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager
    private lateinit var userViewModel: UserViewModel

    private var signInInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        configureSystemBars()
        bindViews()
        applySystemInsets()

        userViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        auth = Firebase.auth
        credentialManager = CredentialManager.create(this)

        loginButton.setOnClickListener {
            if (signInInProgress) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            launchCredentialManager()
        }

        playEntranceAnimation()
    }

    private fun bindViews() {
        loginButton = findViewById(R.id.loginButton)
        loginProgress = findViewById(R.id.loginProgress)
        loginStatus = findViewById(R.id.loginStatus)
        atmosphereBadge = findViewById(R.id.atmosphereBadge)
        loginHero = findViewById(R.id.loginHero)
        loginCard = findViewById(R.id.loginCard)
    }

    private fun configureSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applySystemInsets() {
        val content = findViewById<View>(R.id.loginContent)
        val initialLeft = content.paddingLeft
        val initialTop = content.paddingTop
        val initialRight = content.paddingRight
        val initialBottom = content.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun playEntranceAnimation() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            atmosphereBadge.alpha = 1f
            loginHero.alpha = 1f
            loginCard.alpha = 1f
            return
        }

        atmosphereBadge.alpha = 0f
        atmosphereBadge.translationY = -12f * resources.displayMetrics.density
        loginHero.alpha = 0f
        loginHero.scaleX = 0.97f
        loginHero.scaleY = 0.97f
        loginCard.alpha = 0f
        loginCard.translationY = 34f * resources.displayMetrics.density

        atmosphereBadge.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(90L)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        loginHero.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(170L)
            .setDuration(720L)
            .setInterpolator(DecelerateInterpolator(1.35f))
            .start()

        loginCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(290L)
            .setDuration(680L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    private fun launchCredentialManager() {
        setLoading(true, R.string.login_connecting)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
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
            } catch (_: GetCredentialCancellationException) {
                setLoading(false)
            } catch (error: GetCredentialException) {
                Log.w(TAG, "Google credential flow failed", error)
                showError(R.string.login_error_credentials)
            }
        }
    }

    private fun handleSignIn(credential: Credential) {
        if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            firebaseAuthWithGoogle(googleCredential.idToken)
        } else {
            Log.w(TAG, "Unsupported credential type: ${credential.type}")
            showError(R.string.login_error_invalid_credential)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        setLoading(true, R.string.login_verifying)
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(firebaseCredential)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Firebase authentication failed", task.exception)
                    showError(R.string.login_error_firebase)
                    return@addOnCompleteListener
                }

                completeBackendLogin(idToken)
            }
    }

    private fun completeBackendLogin(idToken: String) {
        val email = auth.currentUser?.email.orEmpty()
        lifecycleScope.launch {
            try {
                val authService = ApiClient.getAuthService(applicationContext)
                val userService = ApiClient.getUserService(applicationContext)
                val response = authService.loginWithGoogle(GoogleTokenDTO(idToken))

                saveTokens(response.accessToken, response.refreshToken, email)

                val userData = userService.getCurrentUser()
                if (userData.isBanned) {
                    clearRestrictedAccount()
                    showError(R.string.login_banned)
                    return@launch
                }

                userViewModel.user = userData
                saveUserData(userData)
                UserManager(applicationContext).saveUserId(userData.id)

                startActivity(
                    Intent(this@LoginActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
            } catch (error: Exception) {
                Log.e(TAG, "Backend authentication failed", error)
                showError(R.string.login_error_backend)
            }
        }
    }

    private fun clearRestrictedAccount() {
        FirebaseAuth.getInstance().signOut()
        userViewModel.user = null
        getSharedPreferences("user_data", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("Auth", MODE_PRIVATE).edit().clear().apply()
    }

    private fun setLoading(loading: Boolean, statusText: Int? = null) {
        signInInProgress = loading
        loginButton.isEnabled = !loading
        loginButton.alpha = if (loading) 0.82f else 1f

        if (loading) {
            loginButton.text = getString(R.string.login_connecting)
            loginButton.icon = null
            loginProgress.visibility = View.VISIBLE
            if (statusText != null) {
                loginStatus.setText(statusText)
                loginStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.login_text_secondary)
                )
                loginStatus.visibility = View.VISIBLE
            }
        } else {
            loginButton.setText(R.string.login_google_action)
            loginButton.setIconResource(R.drawable.ic_google)
            loginProgress.visibility = View.GONE
            if (statusText == null) {
                loginStatus.visibility = View.GONE
            }
        }
    }

    private fun showError(message: Int) {
        setLoading(false)
        loginStatus.setText(message)
        loginStatus.setTextColor(ContextCompat.getColor(this, R.color.login_accent))
        loginStatus.visibility = View.VISIBLE
    }

    private fun saveUserData(userData: User) {
        getSharedPreferences("user_data", MODE_PRIVATE).edit().apply {
            putString("NAME", userData.name)
            putString("EMAIL", userData.email)
            putString("USER_ID", userData.id)
            putBoolean("IS_BANNED", userData.isBanned)
            apply()
        }
    }

    private fun saveTokens(accessToken: String, refreshToken: String, email: String) {
        getSharedPreferences("Auth", MODE_PRIVATE).edit().apply {
            putString("ACCESS_TOKEN", accessToken)
            putString("REFRESH_TOKEN", refreshToken)
            putString("EMAIL", email)
            apply()
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
