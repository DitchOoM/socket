package com.ditchoom.socket.quic.core.life

import kotlin.test.Test
import kotlin.test.assertEquals

class TiersTests {
    @Test
    fun anExceptionEndsItsConnectionOnly() {
        assertEquals(Tier.Connection, Tiers.of(IllegalStateException()))
        assertEquals(Tier.Connection, Tiers.of(RuntimeException()))
    }

    @Test
    fun anErrorIsNotContained() {
        assertEquals(Tier.Process, Tiers.of(Error()))
        assertEquals(Tier.Process, Tiers.of(AssertionError()))
    }
}
