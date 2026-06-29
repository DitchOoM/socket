@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.quiche.quiche_version
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase-0 quiche-on-Apple spike: prove the self-contained macOS `libquiche.a` (vendored BoringSSL)
 * links into a Kotlin/Native binary and its FFI is callable. `quiche_version()` is the cheapest live
 * symbol — exercising it confirms the cinterop binding, the `-force_load` archive link, and the
 * vendored-BoringSSL dependency graph all resolve. The build linking at all additionally proves the
 * CommonCrypto cinterop (the appleMain `sha256Into` actual references `CC_SHA256`).
 */
class QuicheNativeLinkSpikeTest {
    @Test
    fun quicheVersionLinksAndIsCallable() {
        val version = quiche_version()?.toKString()
        assertNotNull(version, "quiche_version() returned null — libquiche.a did not link or bind")
        assertTrue(version.isNotBlank(), "quiche_version() returned a blank string: '$version'")
    }
}
