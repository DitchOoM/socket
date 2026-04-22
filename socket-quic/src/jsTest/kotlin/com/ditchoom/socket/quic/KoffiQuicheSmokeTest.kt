package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end smoke test for the koffi → libquiche FFI path.
 *
 * Browser: early-returns. The FFI loader is Node-only — trying to require('koffi')
 * in a browser test process would fail the moment [quicheLibrary] is touched.
 *
 * Node: loads libquiche via [quicheLibrary] and proves [QuicheApi.configNew] +
 * [QuicheApi.configFree] round-trip via [KoffiQuicheApi], plus
 * asserts [quicheVersion] matches the libs.versions.toml pinned version.
 */
class KoffiQuicheSmokeTest {
    @Test
    fun koffiLoadsLibquicheAndReportsVersion() {
        if (!isNode) return
        val version = quicheVersion()
        assertEquals("0.28.0", version, "libquiche version from FFI must match pinned quicheVersion")
    }

    @Test
    fun configNewAndFreeRoundTripThroughKoffi() {
        if (!isNode) return
        val api = KoffiQuicheApi()
        val cfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        assertNotEquals(0L, cfg.handle, "quiche_config_new must return a non-null pointer")
        assertTrue(cfg.handle > 0L, "pointer address should be positive (fits in int64 user-space)")
        api.configFree(cfg) // would crash with double-free or SIGSEGV if the handle were corrupted
    }

    companion object {
        /** Mirrors the value in CommonJvmQuicEngine; defined locally so this test doesn't pull in commonJvmMain. */
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
    }
}
