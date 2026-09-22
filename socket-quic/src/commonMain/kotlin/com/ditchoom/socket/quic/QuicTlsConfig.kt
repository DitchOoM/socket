package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/** TLS configuration for a QUIC server. */
data class QuicTlsConfig(
    /** Path to PEM-encoded certificate chain file. */
    val certChainPath: String,
    /** Path to PEM-encoded private key file. */
    val privKeyPath: String,
    @Deprecated(
        "Read by no server: every QUIC server, Apple included, loads the PEM certChainPath and " +
            "privKeyPath. Removed in 5.0.",
        level = DeprecationLevel.WARNING,
    )
    val pkcs12Path: String? = null,
    @Deprecated(
        "Read by no server: every QUIC server, Apple included, loads the PEM certChainPath and " +
            "privKeyPath. Removed in 5.0.",
        level = DeprecationLevel.WARNING,
    )
    val pkcs12Password: String? = null,
    /** Which key encrypts the session tickets this server issues — and so which servers can resume them. */
    val sessionTicketKeys: QuicSessionTicketKeys = QuicSessionTicketKeys.PerServer,
)

/**
 * The key a server encrypts its session tickets under (RFC 8446 §4.6.1). A client's ticket resumes on
 * exactly the servers that hold the key it was encrypted under.
 */
sealed interface QuicSessionTicketKeys {
    /**
     * A key this server generates for itself: its tickets resume on any of its connections until it is
     * closed, and not after. The TLS library rotates it every 48 hours.
     */
    data object PerServer : QuicSessionTicketKeys

    /**
     * One key for every server that holds it, so tickets resume across restarts and across servers.
     * Never rotated for you: anyone holding it can decrypt the tickets issued under it, so rotate it
     * yourself.
     *
     * @param material exactly [KEY_BYTES] bytes of secret key material, copied without consuming it.
     * @throws InvalidSessionTicketKeyException when [material] is any other length.
     */
    class Shared(
        material: ReadBuffer,
    ) : QuicSessionTicketKeys {
        private val key: ReadBuffer =
            material.slice().let { source ->
                if (source.remaining() != KEY_BYTES) throw InvalidSessionTicketKeyException(source.remaining())
                BufferFactory.managed().allocate(KEY_BYTES).also {
                    it.write(source)
                    it.resetForRead()
                }
            }

        /** The key material: a read-only view positioned at its first byte. */
        fun material(): ReadBuffer = key.slice()

        override fun toString(): String = "QuicSessionTicketKeys.Shared"

        companion object {
            /** The length of session ticket key material: a 16-byte name, a 16-byte HMAC key and a 16-byte AES key. */
            const val KEY_BYTES: Int = 48
        }
    }
}

/** Session ticket key material that is not [QuicSessionTicketKeys.Shared.KEY_BYTES] long; [length] is what was supplied. */
class InvalidSessionTicketKeyException(
    val length: Int,
) : IllegalArgumentException("session ticket key material is ${QuicSessionTicketKeys.Shared.KEY_BYTES} bytes, not $length")
