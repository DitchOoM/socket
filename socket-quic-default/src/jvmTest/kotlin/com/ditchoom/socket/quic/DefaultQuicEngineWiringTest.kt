package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the JVM/Android wiring of the default-engine bundle: [defaultQuicEngine] must resolve to
 * the quiche backend ([QuicheEngine]) so the public `withQuic*` wrappers drive quiche on this target.
 *
 * Native-free: asserting the actual `val` + its capabilities does NOT load libquiche (that happens
 * lazily inside `connect`/`bind`). The end-to-end behavior of the wrappers is covered by the quiche
 * engine-behavior suites in :socket-quic-quiche, which call `withQuic*` through the test-scope
 * dependency on this module.
 */
class DefaultQuicEngineWiringTest {
    @Test
    fun defaultQuicEngine_isQuicheBackend_onJvm() {
        assertSame(QuicheEngine, defaultQuicEngine, "JVM defaultQuicEngine must be the quiche backend")
    }

    @Test
    fun defaultQuicEngine_advertisesQuicheCapabilities() {
        val caps = defaultQuicEngine.capabilities
        assertTrue(caps.supportsServer, "quiche supports server bind")
        assertTrue(caps.supportsDatagrams, "quiche supports datagrams")
        assertTrue(caps.supportsMigration, "quiche supports connection migration")
    }
}
