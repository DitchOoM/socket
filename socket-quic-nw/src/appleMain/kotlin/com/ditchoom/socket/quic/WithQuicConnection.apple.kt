package com.ditchoom.socket.quic

import platform.Foundation.NSData

/**
 * Advertised `max_datagram_frame_size` (RFC 9221) — the largest a single DATAGRAM
 * frame may be. We advertise the 16-bit maximum; the *usable* per-datagram size is
 * the smaller path-MTU value reported live by [QuicScope.maxDatagramSize].
 *
 * Shared by the OS-26 Swift QUIC client ([connectQuicSwift]) and server
 * ([buildAppleQuicSwiftServer]).
 */
internal const val DATAGRAM_FRAME_SIZE_MAX: UShort = 65535u

/**
 * Parse every `-----BEGIN CERTIFICATE-----` block in [pem] into DER-encoded
 * [NSData] for `SecCertificateCreateWithData`. Issue #81: lets the Apple QUIC
 * verify path pin a private-CA trust anchor without touching the OS keychain.
 *
 * Foundation decodes the base64 body straight into DER-backed [NSData] — no
 * intermediate `ByteArray`.
 */
internal fun pemToDerCertificates(pem: String): List<NSData> {
    val begin = "-----BEGIN CERTIFICATE-----"
    val end = "-----END CERTIFICATE-----"
    val ders = mutableListOf<NSData>()
    var search = 0
    while (true) {
        val b = pem.indexOf(begin, search)
        if (b < 0) break
        val e = pem.indexOf(end, b + begin.length)
        if (e < 0) break
        val body = pem.substring(b + begin.length, e)
        decodeBase64ToNSData(body)?.let { ders += it }
        search = e + end.length
    }
    return ders
}
