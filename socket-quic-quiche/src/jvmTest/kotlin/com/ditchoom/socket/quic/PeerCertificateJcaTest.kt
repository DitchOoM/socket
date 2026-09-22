@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Generated certificates judged by `java.security`, which shares no code with the encoder: it must parse
 * the DER as an X.509 v3 ECDSA P-256 certificate, verify its self-signature, agree field-for-field with the
 * DER walk, hash to [PeerCertificate.hash] under `MessageDigest`, and accept the PEM key file quiche loads
 * as the private half of the certificate's public key.
 */
class PeerCertificateJcaTest {
    private val engine = QuicheEngine.peerCertificates as PeerCertificateSupport.Available

    @Test
    fun javaSecurityParsesVerifiesAndAgreesAcrossManyCertificates() {
        repeat(CERTIFICATES) { index ->
            engine.generate().use { certificate ->
                val bytes = certificate.der().bytes()
                val x509 = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
                val context = "certificate $index: ${bytes.toHex()}"
                assertEquals(3, x509.version, context)
                assertEquals("SHA256withECDSA", x509.sigAlgName, context)
                x509.verify(x509.publicKey) // throws on a bad self-signature
                assertEquals(secp256r1Order(), (x509.publicKey as ECPublicKey).params.order, context)
                assertEquals(listOf("1.3.6.1.5.5.7.3.1"), x509.extendedKeyUsage, context)
                assertEquals(certificate.notBefore, Instant.fromEpochMilliseconds(x509.notBefore.time), context)
                assertEquals(certificate.notAfter, Instant.fromEpochMilliseconds(x509.notAfter.time), context)
                assertTrue(x509.serialNumber.signum() > 0, context)

                val walked = assertNotNull(parsePinnedLeafFieldsDer(certificate.der()), context)
                assertEquals(certificate.notBefore, walked.notBefore, context)
                assertEquals(certificate.notAfter, walked.notAfter, context)
                assertTrue(walked.isEcP256, context)

                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                assertEquals(
                    digest.toHex(),
                    certificate.hash.value
                        .bytes()
                        .toHex(),
                    context,
                )
            }
        }
    }

    @Test
    fun pemKeyFileIsThePrivateHalfOfTheCertificateAndIsDeletedAfterTheBind() {
        engine.generate().use { certificate ->
            val x509 =
                CertificateFactory
                    .getInstance(
                        "X.509",
                    ).generateCertificate(certificate.der().bytes().inputStream()) as X509Certificate
            val seen = mutableListOf<File>()
            val inspecting =
                FilesOnlyEngine { tls ->
                    val key = File(tls.privKeyPath)
                    val cert = File(tls.certChainPath)
                    seen += listOf(key, cert)
                    for (file in seen) {
                        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())), file.path)
                    }
                    val pemCert = CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
                    assertEquals(x509, pemCert)
                    val pkcs8 =
                        Base64.getMimeDecoder().decode(
                            key
                                .readText()
                                .lines()
                                .filterNot { it.startsWith("-----") }
                                .joinToString(""),
                        )
                    val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                    // A signature by the file's key verifies under the certificate's public key.
                    val message = "peer".toByteArray()
                    val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
                    signer.update(message)
                    val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(x509.publicKey) }
                    verifier.update(message)
                    assertTrue(verifier.verify(signer.sign()), "PEM key does not match the certificate")
                }
            runBlocking {
                assertFailsWith<BindInspected> {
                    inspecting.bind(QuicPortBinding.Own(0, null), certificate, QuicOptions(alpnProtocols = listOf("test")), 1.seconds)
                }
            }
            assertEquals(2, seen.size, "the engine was never handed the PEM files")
            for (file in seen) assertFalse(file.exists(), "${file.path} survived the bind")
        }
    }

    /** Runs [inspect] on the files [QuicEngine.bind] is handed, then aborts the bind. */
    private class FilesOnlyEngine(
        private val inspect: (QuicTlsConfig) -> Unit,
    ) : QuicEngine {
        override val capabilities = EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = true)

        override suspend fun connect(
            binding: QuicClientBinding,
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            transport: TransportConfig,
            timeout: Duration,
        ): QuicConnection = throw UnsupportedOperationException("bind-only test engine")

        override suspend fun bind(
            binding: QuicPortBinding,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: Duration,
        ): QuicServer {
            inspect(tlsConfig)
            throw BindInspected()
        }
    }

    private class BindInspected : Exception()

    private fun secp256r1Order() =
        java.security.AlgorithmParameters
            .getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            .order

    private fun ReadBuffer.bytes(): ByteArray = ByteArray(remaining()) { this[position() + it] }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val CERTIFICATES = 64
    }
}
