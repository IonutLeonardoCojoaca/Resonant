package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResonantWidgetActionsTest {

    @Test
    fun `no request code collides between actions or widgets`() {
        val widgetIds = listOf(1, 2, 3, 57, 1000)
        val codes = mutableMapOf<Int, Pair<WidgetButtonAction, Int>>()
        widgetIds.forEach { id ->
            WidgetButtonAction.values().forEach { action ->
                val code = ResonantWidgetActions.requestCodeFor(action, id)
                val previous = codes[code]
                if (previous != null) {
                    // Only the same media key may share a code (same intent, same PendingIntent).
                    assertEquals(action, previous.first)
                    assertTrue(ResonantWidgetActions.isMediaKey(action))
                } else {
                    codes[code] = action to id
                }
            }
        }
    }

    @Test
    fun `openers get one code per widget`() {
        listOf(WidgetButtonAction.OPEN_APP, WidgetButtonAction.OPEN_ROOM, WidgetButtonAction.OPEN_LIVE).forEach { action ->
            assertTrue(ResonantWidgetActions.requestCodeFor(action, 1) != ResonantWidgetActions.requestCodeFor(action, 2))
        }
    }

    @Test
    fun `media keys are play, pause, next and previous only`() {
        assertTrue(ResonantWidgetActions.isMediaKey(WidgetButtonAction.PLAY))
        assertTrue(ResonantWidgetActions.isMediaKey(WidgetButtonAction.PAUSE))
        assertTrue(ResonantWidgetActions.isMediaKey(WidgetButtonAction.NEXT))
        assertTrue(ResonantWidgetActions.isMediaKey(WidgetButtonAction.PREVIOUS))
        assertFalse(ResonantWidgetActions.isMediaKey(WidgetButtonAction.OPEN_APP))
        assertNull(ResonantWidgetActions.keyCodeFor(WidgetButtonAction.OPEN_ROOM))
    }

    @Test
    fun `play and pause map to explicit keys`() {
        // KeyEvent.KEYCODE_MEDIA_PLAY = 126, KEYCODE_MEDIA_PAUSE = 127 (never PLAY_PAUSE = 85).
        assertEquals(126, ResonantWidgetActions.keyCodeFor(WidgetButtonAction.PLAY))
        assertEquals(127, ResonantWidgetActions.keyCodeFor(WidgetButtonAction.PAUSE))
    }
}
