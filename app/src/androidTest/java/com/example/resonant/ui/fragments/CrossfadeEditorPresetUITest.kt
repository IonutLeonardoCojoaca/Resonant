package com.example.resonant.ui.fragments

import android.os.Bundle
import androidx.fragment.app.testing.FragmentScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.example.resonant.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for the Crossfade Editor layout.
 *
 * These tests verify the presence of the core UI components.
 * They require a real or emulated device to run.
 *
 * Note: Full integration tests require mocked backend responses.
 */
@RunWith(AndroidJUnit4::class)
class CrossfadeEditorPresetUITest {

    @Test
    fun editorLayout_inflatesCoreControls() {
        val args = Bundle().apply {
            putString("playmixId", "test-pmx")
            putString("transitionId", "test-t")
        }
        val scenario = FragmentScenario.launchInContainer(
            CrossfadeEditorFragment::class.java,
            args,
            R.style.AppBottomSheetDialogTheme
        )

        // Core controls should exist
        onView(withId(R.id.waveformView)).check(matches(withId(R.id.waveformView)))
        onView(withId(R.id.topSaveButton)).check(matches(withId(R.id.topSaveButton)))
        onView(withId(R.id.previewToggleButton)).check(matches(withId(R.id.previewToggleButton)))
    }
}
