package com.example.resonant.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationBadgeTest {

    @Test
    fun `zero or negative shows no counter`() {
        assertNull(NotificationBadge.format(0))
        assertNull(NotificationBadge.format(-3))
    }

    @Test
    fun `up to 99 shows the number`() {
        assertEquals("1", NotificationBadge.format(1))
        assertEquals("3", NotificationBadge.format(3))
        assertEquals("99", NotificationBadge.format(99))
    }

    @Test
    fun `above 99 shows 99+`() {
        assertEquals("99+", NotificationBadge.format(100))
        assertEquals("99+", NotificationBadge.format(4_000))
    }
}
