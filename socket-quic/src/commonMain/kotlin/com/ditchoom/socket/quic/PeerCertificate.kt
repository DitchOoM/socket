@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A certificate validity period a browser accepts for `serverCertificateHashes`: whole seconds, positive,
 * and at most [MAX_PINNED_CERTIFICATE_VALIDITY] (14 days). A longer one cannot be constructed.
 */
class PeerCertificateValidity private constructor(
    val duration: Duration,
) {
    /** The outcome of [of]. */
    sealed interface Result {
        class Valid(
            val validity: PeerCertificateValidity,
        ) : Result

        /** [requested] is longer than [MAX_PINNED_CERTIFICATE_VALIDITY]. */
        class ExceedsMaximum(
            val requested: Duration,
        ) : Result

        /** [requested] is shorter than one second. */
        class NotPositive(
            val requested: Duration,
        ) : Result
    }

    override fun equals(other: Any?): Boolean = other is PeerCertificateValidity && other.duration == duration

    override fun hashCode(): Int = duration.hashCode()

    override fun toString(): String = "PeerCertificateValidity($duration)"

    companion object {
        /** The longest validity the W3C constraints allow: 14 days. */
        val Maximum: PeerCertificateValidity = PeerCertificateValidity(MAX_PINNED_CERTIFICATE_VALIDITY)

        /** [duration], truncated to whole seconds (X.509 time precision), as a validity. */
        fun of(duration: Duration): Result {
            val whole = duration.inWholeSeconds.seconds
            return when {
                duration > MAX_PINNED_CERTIFICATE_VALIDITY -> Result.ExceedsMaximum(duration)
                whole <= Duration.ZERO -> Result.NotPositive(duration)
                else -> Result.Valid(PeerCertificateValidity(whole))
            }
        }
    }
}

/**
 * How far [PeerCertificateSupport.Available.generate] backdates `notBefore` by default, so a peer whose clock
 * runs ahead of the browser's still presents a currently valid certificate.
 */
val PEER_CERTIFICATE_CLOCK_SKEW_ALLOWANCE: Duration = 1.hours

/** Whether this [QuicEngine] can mint a [PeerCertificate]; read it from [QuicEngine.peerCertificates]. */
sealed interface PeerCertificateSupport {
    /** The engine supplies the crypto; [generate] mints certificates. */
    class Available(
        private val backend: PeerCertificateBackend,
    ) : PeerCertificateSupport {
        /**
         * Generate a fresh self-signed ECDSA P-256 X.509 v3 certificate valid from [notBefore] (truncated
         * to whole seconds) for [validity]. Throws [PeerCertificateException].
         */
        fun generate(
            validity: PeerCertificateValidity = PeerCertificateValidity.Maximum,
            notBefore: Instant = Clock.System.now() - PEER_CERTIFICATE_CLOCK_SKEW_ALLOWANCE,
        ): PeerCertificate = generatePeerCertificate(backend, validity, Instant.fromEpochSeconds(notBefore.epochSeconds))
    }

    /** No certificate can be minted here (the engine has no crypto for it, or no server at all). */
    data object Unavailable : PeerCertificateSupport
}

/**
 * A self-signed certificate meeting the W3C WebTransport `serverCertificateHashes` constraints — X.509 v3,
 * ECDSA P-256, validity at most 14 days — together with its private key, for a QUIC server that browsers
 * reach by hash instead of by CA. Minted by [PeerCertificateSupport.Available.generate]; served by
 * [QuicEngine.bind] (and the `withQuicServer` / `withHttp3Server` overloads that take one).
 *
 * Hand [hash] to the browser (`serverCertificateHashes: [{ algorithm: "sha-256", value }]`) through your
 * signalling channel. The certificate never renews itself: mint a successor before [renewAt] and publish
 * its hash alongside this one while both are valid.
 *
 * The private key lives in native memory from generation until [close], which wipes it. It reaches the TLS
 * stack only during a bind, as owner-only PEM files deleted as soon as the bind returns. [hash], [der],
 * [notBefore] and [notAfter] stay readable after [close].
 */
class PeerCertificate internal constructor(
    private val backend: PeerCertificateBackend,
    private val certificateDer: ReadBuffer,
    private val certificatePem: PlatformBuffer,
    private val privateKeyPem: PlatformBuffer,
    /** Start of the validity window (inclusive). */
    val notBefore: Instant,
    val validity: PeerCertificateValidity,
    /** SHA-256 over the certificate's DER: the `serverCertificateHashes` value. */
    val hash: CertificateHash,
) : AutoCloseable {
    /** End of the validity window: [notBefore] + [validity]. */
    val notAfter: Instant = notBefore + validity.duration

    private val key = AtomicReference<KeyState>(KeyState.Open(0))

    /** The certificate's DER encoding, as a fresh read-only view each call. */
    fun der(): ReadBuffer = certificateDer.slice()

    /** When to have a successor published: [lead] before [notAfter]. */
    fun renewAt(lead: Duration): Instant = notAfter - lead

    /**
     * Write the certificate and key as owner-only PEM files, run [block] with their paths, and delete them
     * when it returns. Throws [PeerCertificateException] with [PeerCertificateFailure.Closed] after [close].
     */
    internal suspend fun <R> withPemFiles(block: suspend (QuicTlsConfig) -> R): R {
        borrow()
        try {
            return backend.withPemFiles(certificatePem, privateKeyPem, block)
        } finally {
            release()
        }
    }

    /** Wipe and free the private key. Idempotent; takes effect once no bind is using the key. */
    override fun close() {
        while (true) {
            when (val state = key.load()) {
                is KeyState.Open ->
                    if (state.borrows == 0) {
                        if (key.compareAndSet(state, KeyState.Released)) return wipe()
                    } else if (key.compareAndSet(state, KeyState.Closing(state.borrows))) {
                        return
                    }
                is KeyState.Closing, KeyState.Released -> return
            }
        }
    }

    override fun toString(): String = "PeerCertificate($hash, $notBefore..$notAfter)"

    private fun borrow() {
        while (true) {
            when (val state = key.load()) {
                is KeyState.Open -> if (key.compareAndSet(state, KeyState.Open(state.borrows + 1))) return
                is KeyState.Closing, KeyState.Released -> throw PeerCertificateException(PeerCertificateFailure.Closed)
            }
        }
    }

    private fun release() {
        while (true) {
            when (val state = key.load()) {
                is KeyState.Open -> if (key.compareAndSet(state, KeyState.Open(state.borrows - 1))) return
                is KeyState.Closing ->
                    if (state.borrows == 1) {
                        if (key.compareAndSet(state, KeyState.Released)) return wipe()
                    } else if (key.compareAndSet(state, KeyState.Closing(state.borrows - 1))) {
                        return
                    }
                // Unreachable: every release follows its own borrow, which excludes Released.
                KeyState.Released -> return
            }
        }
    }

    private fun wipe() {
        privateKeyPem.zeroAndFree()
        certificatePem.freeNativeMemory()
    }

    /** Who may still read the private key. */
    private sealed interface KeyState {
        class Open(
            val borrows: Int,
        ) : KeyState

        /** [close] was called while [borrows] binds were reading the key; the last one wipes it. */
        class Closing(
            val borrows: Int,
        ) : KeyState

        data object Released : KeyState
    }
}

private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
private const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
private const val OID_PRIME256V1 = "1.2.840.10045.3.1.7"
private const val OID_COMMON_NAME = "2.5.4.3"
private const val OID_EXTENDED_KEY_USAGE = "2.5.29.37"
private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

private const val X509_VERSION_3 = 2
private const val PKCS8_VERSION = 0
private const val EC_PRIVATE_KEY_VERSION = 1
private const val EC_PRIVATE_KEY_PUBLIC_KEY_TAG = 1
private const val TBS_EXTENSIONS_TAG = 3
private const val COMMON_NAME = "webtransport-peer"

private const val P256_PUBLIC_KEY_BYTES = 65
private const val P256_PRIVATE_KEY_BYTES = 32
private const val SHA256_BYTES = 32
private const val SERIAL_BYTES = 16
private const val UNCOMPRESSED_POINT = 0x04
private const val DER_SEQUENCE = 0x30
private const val SERIAL_TOP_BITS_CLEAR = 0x3F
private const val SERIAL_POSITIVE_BIT = 0x40

/**
 * Mint the certificate. Key material and every intermediate that holds it are native buffers freed (and,
 * for secrets, zeroed) before this returns; the public [PeerCertificate.der] and [PeerCertificate.hash] are
 * GC-managed copies, so they outlive [PeerCertificate.close].
 */
private fun generatePeerCertificate(
    backend: PeerCertificateBackend,
    validity: PeerCertificateValidity,
    notBefore: Instant,
): PeerCertificate {
    val native = BufferFactory.network()
    val notBeforeDer = Der.time(notBefore)
    val notAfterDer = Der.time(notBefore + validity.duration)
    backend.generateKeyPair().use { keyPair ->
        val publicKey = native.allocate(P256_PUBLIC_KEY_BYTES)
        val serial = native.allocate(SHA256_BYTES)
        try {
            keyPair.writePublicKey(publicKey)
            publicKey.resetForRead()
            if (publicKey.remaining() != P256_PUBLIC_KEY_BYTES || publicKey[0].toInt() != UNCOMPRESSED_POINT) {
                malformed(KeyMaterialPart.PublicKey, publicKey)
            }
            // A positive 16-byte serial that is unique per key: the leading bytes of the key's SHA-256.
            backend.sha256(publicKey, serial)
            serial.set(0, ((serial[0].toInt() and SERIAL_TOP_BITS_CLEAR) or SERIAL_POSITIVE_BIT).toByte())
            serial.position(0)
            serial.setLimit(SERIAL_BYTES)

            val algorithm = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256))
            val name = Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(COMMON_NAME))))
            val p256 = Der.sequence(Der.oid(OID_EC_PUBLIC_KEY), Der.oid(OID_PRIME256V1))
            val tbs =
                Der
                    .sequence(
                        Der.explicit(0, Der.smallInteger(X509_VERSION_3)),
                        Der.unsignedInteger(serial),
                        algorithm,
                        name,
                        Der.sequence(notBeforeDer, notAfterDer),
                        name,
                        Der.sequence(p256, Der.bitString(publicKey)),
                        Der.explicit(
                            TBS_EXTENSIONS_TAG,
                            Der.sequence(
                                Der.sequence(
                                    Der.oid(OID_EXTENDED_KEY_USAGE),
                                    Der.octetString(Der.sequence(Der.oid(OID_SERVER_AUTH))),
                                ),
                            ),
                        ),
                    ).encode(native)
            val signature = native.allocate(P256_MAX_SIGNATURE_BYTES)
            val certificate =
                try {
                    keyPair.sign(tbs, signature)
                    signature.resetForRead()
                    if (signature.remaining() == 0 || signature[0].toInt() != DER_SEQUENCE) {
                        malformed(KeyMaterialPart.Signature, signature)
                    }
                    Der.sequence(Der.encoded(tbs), algorithm, Der.bitString(signature)).encode(native)
                } finally {
                    tbs.freeNativeMemory()
                    signature.freeNativeMemory()
                }
            try {
                val hash = native.allocate(SHA256_BYTES)
                try {
                    backend.sha256(certificate, hash)
                    hash.resetForRead()
                    val certificatePem = pem("CERTIFICATE", certificate, native)
                    val privateKeyPem =
                        try {
                            privateKeyPem(keyPair, publicKey, p256, native)
                        } catch (t: Throwable) {
                            certificatePem.freeNativeMemory()
                            throw t
                        }
                    return PeerCertificate(
                        backend = backend,
                        certificateDer = certificate.managedCopy(),
                        certificatePem = certificatePem,
                        privateKeyPem = privateKeyPem,
                        notBefore = notBefore,
                        validity = validity,
                        hash = CertificateHash(hash.managedCopy()),
                    )
                } finally {
                    hash.freeNativeMemory()
                }
            } finally {
                certificate.freeNativeMemory()
            }
        } finally {
            publicKey.freeNativeMemory()
            serial.freeNativeMemory()
        }
    }
}

private fun malformed(
    part: KeyMaterialPart,
    written: ReadBuffer,
): Nothing = throw PeerCertificateException(PeerCertificateFailure.MalformedKeyMaterial(part, written.remaining()))

/** RFC 5208 PKCS#8 `PrivateKeyInfo` wrapping an RFC 5915 `ECPrivateKey`, as PEM; every intermediate is zeroed. */
private fun privateKeyPem(
    keyPair: P256KeyPair,
    publicKey: ReadBuffer,
    p256: Der,
    native: BufferFactory,
): PlatformBuffer {
    val scalar = native.allocate(P256_PRIVATE_KEY_BYTES)
    try {
        keyPair.writePrivateKey(scalar)
        scalar.resetForRead()
        if (scalar.remaining() != P256_PRIVATE_KEY_BYTES) {
            malformed(KeyMaterialPart.PrivateKey, scalar)
        }
        val pkcs8 =
            Der
                .sequence(
                    Der.smallInteger(PKCS8_VERSION),
                    p256,
                    Der.octetString(
                        Der.sequence(
                            Der.smallInteger(EC_PRIVATE_KEY_VERSION),
                            Der.octetString(scalar),
                            Der.explicit(EC_PRIVATE_KEY_PUBLIC_KEY_TAG, Der.bitString(publicKey)),
                        ),
                    ),
                ).encode(native)
        try {
            return pem("PRIVATE KEY", pkcs8, native)
        } finally {
            pkcs8.zeroAndFree()
        }
    } finally {
        scalar.zeroAndFree()
    }
}

/** A GC-managed copy of the readable bytes, so it survives the native original being freed. */
private fun ReadBuffer.managedCopy(): ReadBuffer {
    val copy = BufferFactory.Default.allocate(remaining())
    for (i in position() until limit()) copy.writeByte(this[i])
    copy.resetForRead()
    return copy
}

private fun PlatformBuffer.zeroAndFree() {
    for (i in 0 until capacity) set(i, 0.toByte())
    freeNativeMemory()
}
