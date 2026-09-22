@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.toHexString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The exact bytes [PeerCertificateSupport.Available.generate] produces for fixed key material, a fixed
 * signature and a fixed "digest", frozen. The vectors were checked outside this codebase:
 * `openssl asn1parse -inform DER` and `openssl x509 -inform DER -text` on [CERTIFICATE_DER], and
 * `openssl pkey -text` on [PRIVATE_KEY_PEM] (which reports the same public point as the certificate).
 * Real-key certificates are parsed back by independent parsers in `:socket-quic-quiche`'s tests.
 */
class PeerCertificateKnownAnswerTests {
    private val notBefore = Instant.parse("2026-09-21T00:00:00Z")

    @Test
    fun certificateDer() {
        val certificate = PeerCertificateSupport.Available(FixedBackend()).generate(PeerCertificateValidity.Maximum, notBefore)
        assertEquals(CERTIFICATE_DER, certificate.der().toHexString())
        assertEquals(notBefore + 14.days, certificate.notAfter)
        assertEquals(FIXED_DIGEST_HEX, certificate.hash.value.toHexString())
    }

    @Test
    fun pemFilesCarryTheCertificateAndPkcs8Key() =
        runTest {
            val backend = FixedBackend()
            val certificate = PeerCertificateSupport.Available(backend).generate(PeerCertificateValidity.Maximum, notBefore)
            val paths = certificate.withPemFiles { it }
            assertEquals(QuicTlsConfig(certChainPath = "cert.pem", privKeyPath = "key.pem"), paths)
            assertEquals(CERTIFICATE_PEM, backend.presentedCertificatePem)
            assertEquals(PRIVATE_KEY_PEM, backend.presentedPrivateKeyPem)
        }

    @Test
    fun closedCertificateRefusesToPresentItsKey() =
        runTest {
            val certificate = PeerCertificateSupport.Available(FixedBackend()).generate(PeerCertificateValidity.Maximum, notBefore)
            certificate.close()
            certificate.close()
            val failure = assertFailsWith<PeerCertificateException> { certificate.withPemFiles { it } }.failure
            assertIs<PeerCertificateFailure.Closed>(failure)
            // The public half outlives the key.
            assertEquals(CERTIFICATE_DER, certificate.der().toHexString())
            assertEquals(FIXED_DIGEST_HEX, certificate.hash.value.toHexString())
        }

    @Test
    fun closeDuringABindWipesOnlyAfterTheBindReturns() =
        runTest {
            val backend = FixedBackend()
            val certificate = PeerCertificateSupport.Available(backend).generate(PeerCertificateValidity.Maximum, notBefore)
            certificate.withPemFiles { certificate.close() }
            // The backend read the key after close() was called inside its bind: still intact.
            assertEquals(PRIVATE_KEY_PEM, backend.presentedPrivateKeyPem)
            assertIs<PeerCertificateFailure.Closed>(assertFailsWith<PeerCertificateException> { certificate.withPemFiles { it } }.failure)
        }

    @Test
    fun malformedBackendKeyMaterialIsTyped() {
        val failure =
            assertFailsWith<PeerCertificateException> {
                PeerCertificateSupport.Available(FixedBackend(publicKeyBytes = 64)).generate(PeerCertificateValidity.Maximum, notBefore)
            }.failure
        val malformed = assertIs<PeerCertificateFailure.MalformedKeyMaterial>(failure)
        assertEquals(KeyMaterialPart.PublicKey, malformed.part)
        assertEquals(64, malformed.actualBytes)
    }

    /** Deterministic stand-in for platform crypto: a fixed real P-256 key pair, a fixed DER "signature", a fixed "digest". */
    private class FixedBackend(
        private val publicKeyBytes: Int = 65,
    ) : PeerCertificateBackend {
        var presentedCertificatePem = ""
        var presentedPrivateKeyPem = ""

        override fun generateKeyPair(): P256KeyPair =
            object : P256KeyPair {
                override fun writePublicKey(dest: WriteBuffer) = writeHex(PUBLIC_POINT_HEX.take(2 * publicKeyBytes), dest)

                override fun writePrivateKey(dest: WriteBuffer) = writeHex(PRIVATE_SCALAR_HEX, dest)

                override fun sign(
                    message: ReadBuffer,
                    dest: WriteBuffer,
                ) {
                    // SEQUENCE { INTEGER 0x11..(32 bytes), INTEGER 0x22..(32 bytes) }
                    dest.writeByte(0x30)
                    dest.writeByte(0x44)
                    for (value in listOf(0x11, 0x22)) {
                        dest.writeByte(0x02)
                        dest.writeByte(0x20)
                        repeat(32) { dest.writeByte(value.toByte()) }
                    }
                }

                override fun close() = Unit
            }

        override fun sha256(
            input: ReadBuffer,
            dest: WriteBuffer,
        ) {
            for (i in 0 until 32) dest.writeByte((0xF0 - i).toByte())
        }

        override suspend fun <R> withPemFiles(
            certificatePem: ReadBuffer,
            privateKeyPem: ReadBuffer,
            block: suspend (QuicTlsConfig) -> R,
        ): R {
            val result = block(QuicTlsConfig(certChainPath = "cert.pem", privKeyPath = "key.pem"))
            // Read after the block, the way a TLS stack loading the files mid-bind would see them.
            presentedCertificatePem = certificatePem.ascii()
            presentedPrivateKeyPem = privateKeyPem.ascii()
            return result
        }

        private fun writeHex(
            hex: String,
            dest: WriteBuffer,
        ) {
            for (i in hex.indices step 2) dest.writeByte(hex.substring(i, i + 2).toInt(16).toByte())
        }

        private fun ReadBuffer.ascii(): String = buildString { for (i in position() until limit()) append(this@ascii[i].toInt().toChar()) }
    }

    private companion object {
        /** An arbitrary P-256 key pair minted by `openssl ecparam -name prime256v1 -genkey`. */
        const val PRIVATE_SCALAR_HEX = "c758ddf932a72005b4886ceb2b755977a94e1539833929a5844b8108efd2ae20"
        const val PUBLIC_POINT_HEX =
            "04249b230beddd0d4e822296b08547a55d6218c588df133bce0fa10fab42955147" +
                "da3b413633f84665d626da6ea763367b0dc598171863e9b6d6c3453f6bb3b0d8"
        const val FIXED_DIGEST_HEX = "f0efeeedecebeae9e8e7e6e5e4e3e2e1e0dfdedddcdbdad9d8d7d6d5d4d3d2d1"
        const val CERTIFICATE_DER =
            "3082014b3081f3a003020102021070efeeedecebeae9e8e7e6e5e4e3e2e1300a06082a8648ce3d040302301c311a3018" +
                "06035504030c117765627472616e73706f72742d70656572301e170d3236303932313030303030305a170d3236313030" +
                "353030303030305a301c311a301806035504030c117765627472616e73706f72742d706565723059301306072a8648ce" +
                "3d020106082a8648ce3d03010703420004249b230beddd0d4e822296b08547a55d6218c588df133bce0fa10fab429551" +
                "47da3b413633f84665d626da6ea763367b0dc598171863e9b6d6c3453f6bb3b0d8a317301530130603551d25040c300a" +
                "06082b06010505070301300a06082a8648ce3d0403020347003044022011111111111111111111111111111111111111" +
                "1111111111111111111111111102202222222222222222222222222222222222222222222222222222222222222222"
        const val CERTIFICATE_PEM =
            "-----BEGIN CERTIFICATE-----\n" +
                "MIIBSzCB86ADAgECAhBw7+7t7Ovq6ejn5uXk4+LhMAoGCCqGSM49BAMCMBwxGjAY\n" +
                "BgNVBAMMEXdlYnRyYW5zcG9ydC1wZWVyMB4XDTI2MDkyMTAwMDAwMFoXDTI2MTAw\n" +
                "NTAwMDAwMFowHDEaMBgGA1UEAwwRd2VidHJhbnNwb3J0LXBlZXIwWTATBgcqhkjO\n" +
                "PQIBBggqhkjOPQMBBwNCAAQkmyML7d0NToIilrCFR6VdYhjFiN8TO84PoQ+rQpVR\n" +
                "R9o7QTYz+EZl1ibabqdjNnsNxZgXGGPpttbDRT9rs7DYoxcwFTATBgNVHSUEDDAK\n" +
                "BggrBgEFBQcDATAKBggqhkjOPQQDAgNHADBEAiARERERERERERERERERERERERER\n" +
                "EREREREREREREREREQIgIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI=\n" +
                "-----END CERTIFICATE-----\n"

        /** Byte-identical to `openssl pkcs8 -topk8 -nocrypt` of the same key. */
        const val PRIVATE_KEY_PEM =
            "-----BEGIN PRIVATE KEY-----\n" +
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgx1jd+TKnIAW0iGzr\n" +
                "K3VZd6lOFTmDOSmlhEuBCO/SriChRANCAAQkmyML7d0NToIilrCFR6VdYhjFiN8T\n" +
                "O84PoQ+rQpVRR9o7QTYz+EZl1ibabqdjNnsNxZgXGGPpttbDRT9rs7DY\n" +
                "-----END PRIVATE KEY-----\n"
    }
}
