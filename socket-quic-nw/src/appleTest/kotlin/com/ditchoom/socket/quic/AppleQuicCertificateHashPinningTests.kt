@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.SocketException
import kotlin.test.assertFailsWith

/**
 * Apple (Network.framework) run of the shared [QuicCertificateHashPinningTestSuite] — common-API parity
 * (Phase 4, Option 1). Exercises the leaf-hash verify_block in `nw_quic_helpers.h` end-to-end against an
 * in-process NW QUIC server using the bundled p12 identity.
 */
class AppleQuicCertificateHashPinningTests : QuicCertificateHashPinningTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    override fun expectedLeafCertHash(): CertificateHash {
        // SHA-256 of testcerts/cert.crt's leaf DER (the p12 identity is generated from the same PEM), a
        // stable fixture computed independently via `openssl x509 -outform DER | openssl dgst -sha256`:
        val bytes = hexToBytes("3e7b7dd003758ae1d66932d3a3ee57d24b1113f35ff63f915ddff6e83a0ad209")
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    /**
     * NW rejects a `serverCertificateHashes` mismatch inside the handshake verify_block; the helper
     * cancels the connection group, surfacing as a connection failure ([SocketException] subtype —
     * `QuicCloseException` from the cancelled group), not the post-handshake [SSLHandshakeFailedException]
     * the quiche backends raise. Assert the connect was rejected.
     */
    override suspend fun assertWrongHashRejected(connect: suspend () -> Unit) {
        assertFailsWith<SocketException> { connect() }
    }

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
