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

    @Test
    fun configSettersCoverPrimitivePassingPaths() {
        if (!isNode) return
        val api = KoffiQuicheApi()
        val cfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        try {
            // Exercise one of each primitive-arg shape so a regression in BigInt/bool/int
            // marshaling surfaces here instead of deep inside connect().
            api.configSetMaxIdleTimeout(cfg, 30_000L) // uint64_t
            api.configSetInitialMaxData(cfg, 10L * 1024 * 1024)
            api.configSetInitialMaxStreamsBidi(cfg, 100L)
            api.configSetInitialMaxStreamsUni(cfg, 100L)
            api.configSetMaxRecvUdpPayloadSize(cfg, 1500L) // size_t
            api.configSetMaxSendUdpPayloadSize(cfg, 1500L)
            api.configVerifyPeer(cfg, false) // bool
            api.configSetDisableActiveMigration(cfg, true)
            api.configEnablePacing(cfg, false)
            api.configEnableHystart(cfg, true)
            api.configSetCcAlgorithm(cfg, 0) // int (0 = QUICHE_CC_RENO)
            api.configEnableEarlyData(cfg) // no args beyond cfg
        } finally {
            api.configFree(cfg)
        }
    }

    @Test
    fun recvInfoAndSendInfoAllocRoundTripThroughKoffi() {
        if (!isNode) return
        val api = KoffiQuicheApi()
        // Use arbitrary non-null sockaddr-looking pointers — recvInfoNew doesn't dereference them.
        val recvInfo = api.recvInfoNew(fromAddr = 0xCAFE1L, fromAddrLen = 16, toAddr = 0xCAFE2L, toAddrLen = 16)
        assertNotEquals(0L, recvInfo.handle)
        api.recvInfoFree(recvInfo)

        val sendInfo = api.sendInfoNew()
        assertNotEquals(0L, sendInfo.handle)
        // &info->to == info + 136; verify our computed offset matches the C layout.
        assertEquals(sendInfo.handle + 136L, api.sendInfoToAddr(sendInfo))
        // Freshly allocated send_info is zero-filled — to_len starts at 0.
        assertEquals(0, api.sendInfoToAddrLen(sendInfo))
        api.sendInfoFree(sendInfo)
    }

    companion object {
        /** Mirrors the value in CommonJvmQuicEngine; defined locally so this test doesn't pull in commonJvmMain. */
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
    }
}
