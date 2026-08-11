package com.example.resonant

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.toNightMode
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ResonantApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debe aplicarse antes de que se cree cualquier Activity para que no
        // haya parpadeo entre el tema por defecto y el elegido por el usuario.
        val themeMode = SettingsManager(this).getThemeModeSync()
        AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
    }
}
