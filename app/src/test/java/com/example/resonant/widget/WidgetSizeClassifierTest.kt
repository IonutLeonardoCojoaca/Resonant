package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeClassifierTest {

    @Test
    fun `width threshold at 180 dp`() {
        assertEquals(WidgetSize.COMPACT, WidgetSizeClassifier.classify(179, 200))
        assertEquals(WidgetSize.LARGE, WidgetSizeClassifier.classify(180, 200))
    }

    @Test
    fun `height threshold at 110 dp`() {
        assertEquals(WidgetSize.HORIZONTAL, WidgetSizeClassifier.classify(250, 109))
        assertEquals(WidgetSize.LARGE, WidgetSizeClassifier.classify(250, 110))
    }

    @Test
    fun `narrow wins over tall`() {
        assertEquals(WidgetSize.COMPACT, WidgetSizeClassifier.classify(110, 40))
        assertEquals(WidgetSize.COMPACT, WidgetSizeClassifier.classify(179, 109))
    }

    @Test
    fun `missing options default to horizontal`() {
        assertEquals(WidgetSize.HORIZONTAL, WidgetSizeClassifier.classify(0, 0))
    }
}
