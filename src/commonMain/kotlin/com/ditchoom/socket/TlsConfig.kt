package com.ditchoom.socket

/**
 * Configuration for TLS/SSL connections.
 *
 * By default, all security validations are enabled. For development and testing,
 * use [INSECURE] to disable certificate validation.
 *
 * **Warning:** Never use [INSECURE] in production environments.
 */
data class TlsConfig(
    /** Verify server certificate against trusted CAs. */
    val verifyCertificates: Boolean = true,
    /** Verify that the certificate hostname matches the connection hostname. */
    val verifyHostname: Boolean = true,
    /** Allow connections to servers with expired certificates. */
    val allowExpiredCertificates: Boolean = false,
    /** Allow connections to servers with self-signed certificates. */
    val allowSelfSigned: Boolean = false,
) {
    companion object {
        /** Default: all security validation enabled. */
        val DEFAULT = TlsConfig()

        /** All validation disabled. For development/testing only. */
        val INSECURE =
            TlsConfig(
                verifyCertificates = false,
                verifyHostname = false,
                allowExpiredCertificates = true,
                allowSelfSigned = true,
            )
    }

    /** Returns `true` if any security validation is disabled. */
    fun isInsecure(): Boolean = !verifyCertificates || !verifyHostname || allowExpiredCertificates || allowSelfSigned
}
