@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic

/**
 * Apple (Network.framework) run of the shared [QuicCertificateHashPinningTestSuite] — common-API parity
 * (Phase 4, Option 1). Exercises the leaf-hash verify_block in `nw_quic_helpers.h` end-to-end against an
 * in-process NW QUIC server using the bundled p12 identity. The reject path throws the same
 * [com.ditchoom.socket.CertificateHashPinningException] as the quiche backends (mapped from the
 * verify_block's reason out-param), so no per-platform assertion override is needed.
 */
class AppleQuicCertificateHashPinningTests : QuicCertificateHashPinningTestSuite() {
    override fun fixtureTlsConfig(name: String) = appleQuicPinnedTlsConfig(name)

    override fun fixtureLeafHash(name: String): CertificateHash {
        // The build writes `<name>.sha256` (lowercase hex of the leaf DER, computed by java.security —
        // an impl independent of the verify_block under test) alongside the cert, so the K/N test reads
        // the expected pin from disk rather than hard-coding a hash that drifts every regeneration.
        val bytes = hexToBytes(appleReadFileText(appleTestCertPath("$name.sha256")).trim())
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    // Apple enforces the W3C constraints once its Security.framework extraction lands (step 4); until
    // then the verify_block checks the leaf hash only, so the constraint-reject tests skip.
    override fun enforcesW3cConstraints() = false

    /** Skip on `--standalone` Apple simulators (see [shouldSkipQuicHarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }
}
