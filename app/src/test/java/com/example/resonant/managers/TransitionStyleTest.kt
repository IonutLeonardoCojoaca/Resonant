package com.example.resonant.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionStyleTest {

    @Test
    fun `nothing stored defaults to automatic`() {
        assertEquals(TransitionStyle.AUTO, TransitionStyle.fromStored(null, null))
    }

    @Test
    fun `legacy values map to the new styles`() {
        assertEquals(TransitionStyle.AUTO, TransitionStyle.fromStored("INTELLIGENT_EQ", 0))
        assertEquals(TransitionStyle.CLEAN_CUT, TransitionStyle.fromStored("DIRECT_CUT", 5))
        assertEquals(TransitionStyle.CLEAN_CUT, TransitionStyle.fromStored("SOFT_MIX", 0)) // antes "0 s = desactivado"
        assertEquals(TransitionStyle.FADE, TransitionStyle.fromStored("SOFT_MIX", 8))
        assertEquals(TransitionStyle.FADE, TransitionStyle.fromStored(null, 5))          // solo tocó el deslizador
        assertEquals(TransitionStyle.FADE, TransitionStyle.fromStored("VALOR_RARO", 5))
    }

    @Test
    fun `each style drives the matching engine mode`() {
        assertEquals(CrossfadeMode.DIRECT_CUT, TransitionStyle.CLEAN_CUT.crossfadeMode)
        assertEquals(CrossfadeMode.SOFT_MIX, TransitionStyle.FADE.crossfadeMode)
        assertEquals(CrossfadeMode.INTELLIGENT_EQ, TransitionStyle.AUTO.crossfadeMode)
    }

    @Test
    fun `fade seconds are bounded with a sensible default`() {
        assertEquals(6, TransitionStyle.fadeSeconds(null))
        assertEquals(6, TransitionStyle.fadeSeconds(0))
        assertEquals(8, TransitionStyle.fadeSeconds(8))
        assertEquals(12, TransitionStyle.fadeSeconds(30))
    }
}
