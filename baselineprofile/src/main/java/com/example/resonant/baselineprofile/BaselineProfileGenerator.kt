package com.example.resonant.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorrido que se precompila en el Baseline Profile: arranque, Explorar (scroll) y la lista de PlayMix con su
 * detalle. Cada paso es tolerante: si la pantalla no está (p. ej. sesión sin iniciar en el emulador), se salta,
 * y el perfil cubre al menos el arranque.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
        maxIterations = 8
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // Explorar: pestaña + scroll del contenido.
        if (device.tap(text = "Explorar")) {
            device.scrollMainContent(times = 3)
            device.tap(text = "Inicio")
        }

        // PlayMix: lista y primer detalle.
        if (device.tap(description = "PlayMix") || device.tap(text = "PlayMix")) {
            device.wait(Until.hasObject(By.res(PACKAGE, "playmixRecyclerView")), 5_000)
            device.findObject(By.res(PACKAGE, "playmixRecyclerView"))?.children?.firstOrNull()?.click()
            device.waitForIdle()
            device.scrollMainContent(times = 2)
            device.pressBack()
        }
    }

    private fun UiDevice.tap(text: String? = null, description: String? = null): Boolean {
        val selector = when {
            text != null -> By.text(text)
            description != null -> By.desc(description)
            else -> return false
        }
        val target = wait(Until.findObject(selector), 2_000) ?: return false
        target.click()
        waitForIdle()
        return true
    }

    private fun UiDevice.scrollMainContent(times: Int) {
        val scrollable = wait(Until.findObject(By.scrollable(true)), 3_000) ?: return
        repeat(times) {
            scrollable.fling(Direction.DOWN)
            waitForIdle()
        }
        repeat(times) {
            scrollable.fling(Direction.UP)
            waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "com.example.resonant"
    }
}
