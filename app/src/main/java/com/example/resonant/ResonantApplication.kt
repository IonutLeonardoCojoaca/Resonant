package com.example.resonant

import android.app.Application
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.toNightMode
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ResonantApplication : Application() {
    override fun onCreate() {
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()
        // Debe aplicarse antes de que se cree cualquier Activity para que no
        // haya parpadeo entre el tema por defecto y el elegido por el usuario.
        // Lectura de disco deliberada en Main (una sola vez, antes del primer frame).
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            val themeMode = SettingsManager(this).getThemeModeSync()
            AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    /**
     * Solo en debug: deja en Logcat (tag `StrictMode`) cada lectura/escritura de disco, llamada de red o
     * llamada lenta en el hilo principal, y las fugas de Activity/objetos cerrables. No cambia el comportamiento.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }
}
