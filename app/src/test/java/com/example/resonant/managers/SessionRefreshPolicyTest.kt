package com.example.resonant.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRefreshPolicyTest {

    @Test
    fun `an invalid or revoked refresh token ends the session`() {
        assertTrue(SessionManager.isRefreshRejected(401))
        assertTrue(SessionManager.isRefreshRejected(403))
        assertTrue(SessionManager.isRefreshRejected(400))
    }

    @Test
    fun `a server or transient failure keeps the session`() {
        assertFalse(SessionManager.isRefreshRejected(500))
        assertFalse(SessionManager.isRefreshRejected(502))
        assertFalse(SessionManager.isRefreshRejected(503))
        assertFalse(SessionManager.isRefreshRejected(408))
        assertFalse(SessionManager.isRefreshRejected(429))
    }
}
