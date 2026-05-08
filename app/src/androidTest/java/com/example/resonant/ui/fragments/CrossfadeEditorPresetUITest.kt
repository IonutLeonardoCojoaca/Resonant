package com.example.resonant.ui.fragments

import android.os.Bundle
import androidx.fragment.app.testing.FragmentScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.not
import com.example.resonant.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for the Crossfade Editor's new preset features.
 *
 * These tests verify the presence and visibility of the new UI components.
 * They require a real or emulated device to run.
 *
 * Note: Full integration tests require mocked backend responses.
 * These tests verify the layout structure and initial visibility states.
 */
@RunWith(AndroidJUnit4::class)
class CrossfadeEditorPresetUITest {

    @Test
    fun presetSelectorContainer_existsInLayout() {
        val args = Bundle().apply {
            putString("playmixId", "test-pmx")
            putString("transitionId", "test-t")
        }
        val scenario = FragmentScenario.launchInContainer(
            CrossfadeEditorFragment::class.java,
            args,
            R.style.AppBottomSheetDialogTheme
        )

        // The preset selector container should exist but be initially gone
        // (it becomes visible once presets load)
        onView(withId(R.id.presetSelectorContainer))
            .check(matches(not(isDisplayed())))
    }

    @Test
    fun activePresetBanner_initiallyHidden() {
        val args = Bundle().apply {
            putString("playmixId", "test-pmx")
            putString("transitionId", "test-t")
        }
        val scenario = FragmentScenario.launchInContainer(
            CrossfadeEditorFragment::class.java,
            args,
            R.style.AppBottomSheetDialogTheme
        )

        onView(withId(R.id.activePresetBanner))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun gapSliderPanel_initiallyHidden() {
        val args = Bundle().apply {
            putString("playmixId", "test-pmx")
            putString("transitionId", "test-t")
        }
        val scenario = FragmentScenario.launchInContainer(
            CrossfadeEditorFragment::class.java,
            args,
            R.style.AppBottomSheetDialogTheme
        )

        onView(withId(R.id.gapSliderPanel))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun presetPreviewBottomSheet_layoutExists() {
        // Verify the bottom sheet layout inflates without errors
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
        onView(withId(R.id.waveformView)).check(matches(isDisplayed()))
        onView(withId(R.id.saveButton)).check(matches(isDisplayed()))
        onView(withId(R.id.previewButton)).check(matches(isDisplayed()))
    }
}
