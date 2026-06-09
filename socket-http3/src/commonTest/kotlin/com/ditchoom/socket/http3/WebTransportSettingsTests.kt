package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 0 — pure, deterministic tests for WebTransport SETTINGS (no I/O): the [Http3Settings]
 * capability accessors and the [webTransportSettings] advertisement builder.
 */
class WebTransportSettingsTests {
    private fun settings(vararg pairs: Pair<Long, Long>): Http3Settings = Http3Settings(pairs.map { Http3Setting(it.first, it.second) })

    @Test
    fun accessors_readEachSetting() {
        val s =
            settings(
                Http3SettingId.ENABLE_CONNECT_PROTOCOL to 1L,
                Http3SettingId.H3_DATAGRAM to 1L,
                Http3SettingId.WEBTRANSPORT_MAX_SESSIONS to 42L,
            )
        assertTrue(s.enableConnectProtocol)
        assertTrue(s.h3DatagramEnabled)
        assertEquals(42L, s.wtMaxSessions)
    }

    @Test
    fun defaults_whenAbsent() {
        val s = Http3Settings(emptyList())
        assertFalse(s.enableConnectProtocol)
        assertFalse(s.h3DatagramEnabled)
        assertEquals(0L, s.wtMaxSessions)
        assertFalse(s.webTransportSupported)
    }

    @Test
    fun webTransportSupported_requiresAllThree() {
        // All three present and positive ⇒ supported.
        assertTrue(
            settings(
                Http3SettingId.ENABLE_CONNECT_PROTOCOL to 1L,
                Http3SettingId.H3_DATAGRAM to 1L,
                Http3SettingId.WEBTRANSPORT_MAX_SESSIONS to 1L,
            ).webTransportSupported,
        )
        // Missing Extended CONNECT.
        assertFalse(
            settings(
                Http3SettingId.H3_DATAGRAM to 1L,
                Http3SettingId.WEBTRANSPORT_MAX_SESSIONS to 1L,
            ).webTransportSupported,
        )
        // Missing H3 datagrams.
        assertFalse(
            settings(
                Http3SettingId.ENABLE_CONNECT_PROTOCOL to 1L,
                Http3SettingId.WEBTRANSPORT_MAX_SESSIONS to 1L,
            ).webTransportSupported,
        )
        // Connect + datagrams but a zero session limit ⇒ peer accepts no sessions.
        assertFalse(
            settings(
                Http3SettingId.ENABLE_CONNECT_PROTOCOL to 1L,
                Http3SettingId.H3_DATAGRAM to 1L,
                Http3SettingId.WEBTRANSPORT_MAX_SESSIONS to 0L,
            ).webTransportSupported,
        )
    }

    @Test
    fun webTransportSettings_advertisesTheFourEntries() {
        val entries = webTransportSettings(WebTransportOptions(maxSessions = 5))
        assertEquals(
            mapOf(
                Http3SettingId.ENABLE_CONNECT_PROTOCOL to 1L,
                Http3SettingId.H3_DATAGRAM to 1L,
                Http3SettingId.ENABLE_WEBTRANSPORT to 1L,
                Http3SettingId.WEBTRANSPORT_MAX_SESSIONS to 5L,
            ),
            entries.associate { it.identifier to it.value },
        )
    }

    @Test
    fun webTransportSettings_initiateOnly_advertisesZeroSessionsButStaysCapable() {
        // maxSessions = 0 (initiate-only) still advertises connect-protocol + datagrams, so the
        // peer can open sessions toward us even though we accept none inbound.
        val entries = webTransportSettings(WebTransportOptions(maxSessions = 0))
        val peer = Http3Settings(entries)
        assertTrue(peer.enableConnectProtocol)
        assertTrue(peer.h3DatagramEnabled)
        assertEquals(0L, peer.wtMaxSessions)
        // Our own webTransportSupported view of *that* peer is false (it accepts no sessions),
        // which is the correct gate for whether we may initiate toward it.
        assertFalse(peer.webTransportSupported)
    }

    @Test
    fun webTransportOptions_rejectsNegativeMaxSessions() {
        assertFailsWith<IllegalArgumentException> { WebTransportOptions(maxSessions = -1) }
    }
}
