package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use

/**
 * Write the supplied CA PEM blocks to a single temp bundle file and return its path.
 *
 * quiche/BoringSSL only loads verification anchors from a file path, so the in-memory PEM
 * must land on disk; the caller deletes it once `load_verify_locations` has read it. The
 * byte encoding happens inside `java.io` at this file boundary — no `ByteArray` in our code.
 */
private fun writeCaBundleToTempFile(pems: List<String>): String {
    val file = java.io.File.createTempFile("ditchoom-quic-ca", ".pem")
    file.deleteOnExit()
    file.writeText(pems.joinToString("\n"))
    return file.absolutePath
}

/**
 * Load the platform's default CA trust anchors as PEM blocks (the Android CA-trust fix).
 *
 * quiche's bundled BoringSSL has no default verify paths, so a `verify_peer = true` handshake with no
 * caller-pinned anchors would have an empty trust store and reject every real server. We materialise
 * the platform default store ourselves: [TrustManagerFactory] with a null key store yields the JDK
 * `cacerts` on a desktop JVM and the system `AndroidCAStore` on Android, and we PEM-encode each
 * trusted root for [writeCaBundleToTempFile] → `load_verify_locations`.
 *
 * Returns an empty list if no default trust manager / no anchors are available, in which case the
 * caller loads nothing and the handshake behaves as before (BoringSSL default paths, if any).
 */
private fun loadPlatformDefaultCaCertificatesPem(): List<String> =
    runCatching {
        val tmf =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                )
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers
            .filterIsInstance<javax.net.ssl.X509TrustManager>()
            .flatMap { it.acceptedIssuers.asList() }
            .distinct()
            .map { encodeCertificateToPem(it) }
    }.getOrDefault(emptyList())

/**
 * PEM-encode a single X.509 certificate (`-----BEGIN CERTIFICATE-----` … base64 DER … `-----END…`).
 *
 * The DER `ByteArray` here is the certificate's own encoding crossing the `java.security` ↔ Base64
 * boundary, not a hot-path buffer — it never touches socket I/O.
 */
private fun encodeCertificateToPem(cert: java.security.cert.X509Certificate): String {
    @Suppress("NoByteArrayInProd") // java.security.cert API surface: getEncoded() returns DER bytes
    val der = cert.encoded
    val base64 =
        java.util.Base64
            .getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(der)
    return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----"
}

/**
 * The client's CA trust anchors: the caller's pinned PEM bundle if it gave one, else, when the peer is verified,
 * the JVM's or Android's default store. quiche bundles BoringSSL with no default verify paths and loads anchors
 * only from a file, so the bundle goes through a temporary file.
 */
internal actual fun loadTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: com.ditchoom.buffer.BufferFactory,
) {
    val caPems =
        when {
            quicOptions.trustedCaCertificatesPem.isNotEmpty() -> quicOptions.trustedCaCertificatesPem
            resolveVerifyPeer(quicOptions) -> loadPlatformDefaultCaCertificatesPem()
            else -> emptyList()
        }
    if (caPems.isNotEmpty()) {
        val caBundlePath = writeCaBundleToTempFile(caPems)
        try {
            writeNullTerminatedString(caBundlePath, bufferFactory).use { caBuf ->
                val rc = api.configLoadVerifyLocationsFromFile(config, caBuf.nativeMemoryAccess!!.nativeAddress.toLong())
                check(rc == 0) { "Failed to load trusted CA certificates: $rc" }
            }
        } finally {
            runCatching { java.io.File(caBundlePath).delete() }
        }
    }
}

internal fun writeNullTerminatedString(
    str: String,
    factory: BufferFactory,
): PlatformBuffer {
    val buf = factory.allocate(str.length + 1)
    buf.writeString(str, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}
