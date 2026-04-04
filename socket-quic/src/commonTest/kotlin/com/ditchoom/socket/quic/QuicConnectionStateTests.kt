package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuicConnectionStateTests {
    // --- State identity ---

    @Test
    fun idle_isSingleton() {
        assertIs<QuicConnectionState.Idle>(QuicConnectionState.Idle)
    }

    @Test
    fun handshaking_isSingleton() {
        assertIs<QuicConnectionState.Handshaking>(QuicConnectionState.Handshaking)
    }

    @Test
    fun draining_isSingleton() {
        assertIs<QuicConnectionState.Draining>(QuicConnectionState.Draining)
    }

    @Test
    fun established_carriesAlpn() {
        val state = QuicConnectionState.Established("h3")
        assertEquals("h3", state.negotiatedAlpn)
    }

    @Test
    fun established_differentAlpn() {
        val state = QuicConnectionState.Established("h3-29")
        assertEquals("h3-29", state.negotiatedAlpn)
    }

    // --- Closed state ---

    @Test
    fun closed_withNullError_isCleanShutdown() {
        val state = QuicConnectionState.Closed(null)
        assertTrue(state.isCleanShutdown)
        assertNull(state.error)
    }

    @Test
    fun closed_withNoError_isCleanShutdown() {
        val state = QuicConnectionState.Closed(QuicError.NoError)
        assertTrue(state.isCleanShutdown)
    }

    @Test
    fun closed_withRealError_isNotCleanShutdown() {
        val state = QuicConnectionState.Closed(QuicError.ProtocolViolation)
        assertFalse(state.isCleanShutdown)
    }

    @Test
    fun closed_withConnectionRefused_isNotCleanShutdown() {
        val state = QuicConnectionState.Closed(QuicError.ConnectionRefused)
        assertFalse(state.isCleanShutdown)
    }

    @Test
    fun closed_withCryptoError_isNotCleanShutdown() {
        val state = QuicConnectionState.Closed(QuicError.CryptoError(40))
        assertFalse(state.isCleanShutdown)
    }

    @Test
    fun closed_withPlatformError_isNotCleanShutdown() {
        val state = QuicConnectionState.Closed(QuicError.PlatformError(RuntimeException()))
        assertFalse(state.isCleanShutdown)
    }

    // --- Exhaustive when coverage ---

    @Test
    fun allStates_areHandledExhaustively() {
        val states =
            listOf(
                QuicConnectionState.Idle,
                QuicConnectionState.Handshaking,
                QuicConnectionState.Established("h3"),
                QuicConnectionState.Draining,
                QuicConnectionState.Closed(null),
            )
        states.forEach { state ->
            // Exhaustive when — compiler enforces all branches
            val description =
                when (state) {
                    is QuicConnectionState.Idle -> "idle"
                    is QuicConnectionState.Handshaking -> "handshaking"
                    is QuicConnectionState.Established -> "established(${state.negotiatedAlpn})"
                    is QuicConnectionState.Draining -> "draining"
                    is QuicConnectionState.Closed -> "closed(clean=${state.isCleanShutdown})"
                }
            assertTrue(description.isNotEmpty())
        }
    }
}
