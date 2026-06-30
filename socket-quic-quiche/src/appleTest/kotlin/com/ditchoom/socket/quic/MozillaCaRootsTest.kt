@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_load_verify_locations_from_file
import com.ditchoom.socket.quic.quiche.quiche_config_new
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic (no-network) proof that the embedded Mozilla CA bundle is generated, embedded into
 * the Apple klib, and accepted by quiche's BoringSSL — the building block behind iOS `verifyPeer=true`
 * trust (see [loadAppleSystemCaTrust]). Runs on macosArm64 AND iosSimulatorArm64; the end-to-end
 * public-server handshake is covered by the network-gated `QuicPublicEndpointInteropTests`.
 */
class MozillaCaRootsTest {
    @Test
    fun embeddedBundleHasManyRoots() {
        val count = Regex("-----BEGIN CERTIFICATE-----").findAll(MOZILLA_CA_ROOTS_PEM).count()
        assertTrue(count >= 100, "expected >=100 embedded Mozilla roots, found $count")
    }

    @Test
    fun embeddedBundleLoadsIntoQuicheConfig() {
        // BoringSSL only loads anchors from a file; write the embedded PEM to the process's writable
        // temp dir (TMPDIR — /tmp is not writable on the iOS simulator) and load it, mirroring the
        // production loadAppleSystemCaTrust path.
        val dir = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
        val path = "$dir/ditchoom-mozilla-ca-test.pem"
        val file = fopen(path, "w") ?: error("Cannot write temp CA bundle to $path")
        try {
            fputs(MOZILLA_CA_ROOTS_PEM, file)
        } finally {
            fclose(file)
        }
        val config = quiche_config_new(QUICHE_PROTOCOL_VERSION.convert()) ?: error("quiche_config_new failed")
        try {
            val rc = quiche_config_load_verify_locations_from_file(config, path)
            assertEquals(0, rc, "BoringSSL should accept the embedded Mozilla CA bundle (rc=$rc)")
        } finally {
            quiche_config_free(config)
            unlink(path)
        }
    }
}
