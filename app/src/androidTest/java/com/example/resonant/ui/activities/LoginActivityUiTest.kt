package com.example.resonant.ui.activities

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.resonant.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginActivityUiTest {

    @Test
    fun loginShowsResonantExperienceAndGoogleEntryPoint() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf(
                    R.id.resonantFlow,
                    R.id.resonantLogo,
                    R.id.loginHeroTitle,
                    R.id.loginCard,
                    R.id.loginButton,
                ).forEach { viewId ->
                    assertTrue(activity.findViewById<View>(viewId).isShown)
                }
            }
        }
    }
}
