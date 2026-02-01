package com.ditchoom.socket

/**
 * Configuration options for TLS/SSL connections.
 *
 * By default, all security validations are enabled. For development and testing,
 * use [INSECURE] to disable certificate validation.
 *
 * **Warning:** Never use [INSECURE] in production environments.
 *
 * Example usage:
 * ```kotlin
 * // Production (default - all validation enabled)
 * ClientSocket.connect(443, "example.com", tls = true)
 *
 * // Development/Testing (validation disabled)
 * ClientSocket.connect(443, "self-signed.example.com", tls = true, tlsOptions = TlsOptions.INSECURE)
 * ```
 */
data class TlsOptions(
    /**
     * Verify server certificate against trusted Certificate Authorities.
     *
     * When `true` (default), the server's certificate chain is validated against
     * the system's trusted CA store. Self-signed certificates will be rejected.
     *
     * Set to `false` only for testing with self-signed or invalid certificates.
     */
    val verifyCertificates: Boolean = true,
    /**
     * Verify that the certificate's hostname matches the connection hostname.
     *
     * When `true` (default), ensures the certificate was issued for the
     * hostname being connected to, preventing man-in-the-middle attacks.
     *
     * Set to `false` only when connecting to servers with mismatched certificates.
     */
    val verifyHostname: Boolean = true,
    /**
     * Allow connections to servers with expired certificates.
     *
     * When `false` (default), certificates that have expired will be rejected.
     *
     * Set to `true` only for testing with expired certificates.
     * **Warning:** Expired certificates may indicate a security issue.
     */
    val allowExpiredCertificates: Boolean = false,
    /**
     * Allow connections to servers with self-signed certificates.
     *
     * When `false` (default), self-signed certificates (not signed by a trusted CA)
     * will be rejected.
     *
     * Set to `true` only for development/testing with self-signed certificates.
     */
    val allowSelfSigned: Boolean = false,
) {
    companion object {
        /**
         * Default TLS options with all security validation enabled.
         *
         * Use this for production environments.
         */
        val DEFAULT = TlsOptions()

        /**
         * Insecure TLS options with all validation disabled.
         *
         * **Warning:** Only use for development and testing. Never use in production.
         *
         * This disables:
         * - Certificate verification against trusted CAs
         * - Hostname verification
         * - Expired certificate rejection
         * - Self-signed certificate rejection
         */
        val INSECURE =
            TlsOptions(
                verifyCertificates = false,
                verifyHostname = false,
                allowExpiredCertificates = true,
                allowSelfSigned = true,
            )
    }

    /**
     * Returns `true` if any security validation is disabled.
     */
    fun isInsecure(): Boolean = !verifyCertificates || !verifyHostname || allowExpiredCertificates || allowSelfSigned
}
