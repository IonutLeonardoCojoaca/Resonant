package com.example.resonant.autodownload

import androidx.work.NetworkType
import com.example.resonant.managers.AutoDownloadSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDownloadConstraintSpecTest {

    @Test
    fun `defaults require wifi and storage not low`() {
        assertEquals(
            AutoDownloadConstraintSpec(NetworkType.UNMETERED, requiresCharging = false, requiresStorageNotLow = true),
            AutoDownloadConstraintSpec.from(AutoDownloadSettings())
        )
    }

    @Test
    fun `mobile data and charging follow the preferences`() {
        val spec = AutoDownloadConstraintSpec.from(AutoDownloadSettings(wifiOnly = false, requiresCharging = true))

        assertEquals(NetworkType.CONNECTED, spec.networkType)
        assertEquals(true, spec.requiresCharging)
        assertEquals(true, spec.requiresStorageNotLow)
    }
}
