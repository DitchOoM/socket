package com.ditchoom.socket.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the type-gated capability model (RFC_UNIFIED_ESTABLISHMENT.md §3.4): a multiplexing transport is
 * discovered by `is MultiplexingTransport`, and a single-stream-only transport (TCP) must NOT falsely
 * advertise the capability — no stub. This is the fix for the "two-tier leak": a library holds a
 * [Transport] and branches by `is`-check with no risk of a throwing mux stub.
 */
class TransportCapabilityTest {
    @Test
    fun tcp_isSingleStreamOnly_notMultiplexing() {
        val tcp: Transport = TcpTransport()
        assertTrue(tcp is Transport)
        assertFalse(tcp is MultiplexingTransport, "TCP has no multiplexing — it must not implement MultiplexingTransport")
    }
}
