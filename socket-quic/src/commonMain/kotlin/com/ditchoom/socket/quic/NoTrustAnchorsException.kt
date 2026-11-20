package com.ditchoom.socket.quic

/**
 * A client that verifies its peer, with no CAs pinned, found no system trust store on the host: none of [searched]
 * exists. Pin CAs in [QuicOptions.trustedCaCertificatesPem] or install the host's CA certificates.
 */
class NoTrustAnchorsException(
    val searched: List<String>,
) : IllegalStateException("no system CA certificates to verify the peer against; looked at $searched")
