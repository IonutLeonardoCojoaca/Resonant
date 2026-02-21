package com.example.resonant.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.SessionManager

class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep the splash screen visible until we decide where to go
        // In this case, the check is synchronous (SharedPreferences), so it's instant.
        // We probably don't even need setKeepOnScreenCondition(true) since we route immediately.
        
        val session = SessionManager(applicationContext, ApiClient.baseUrl())
        val isLoggedIn = session.hasLocalCredentials()

        if (isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        finish()
    }
}