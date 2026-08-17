package com.ditchoom.socket.quic

/**
 * The seam from a [QuicConnection] back to the [QuicheDriver] behind it.
 *
 * Every connection this module returns is driver-backed — [JvmQuicConnection] (JVM **and** Android),
 * `LinuxQuicConnection`, `AppleQuicConnection`, and the server-side [DriverQuicConnection] — but each
 * holds its driver privately, so there was no platform-agnostic way to reach it. That is fine for
 * production, where nothing outside a connection needs the driver, and a problem for a `commonTest`
 * that must assert a quiche-level fact on a **live** connection across all four backends.
 *
 * Deliberately a separate `internal` interface rather than a member of [QuicConnection]: the public
 * interface has test doubles in five other modules, none of which have a quiche driver to hand, and
 * adding a member there would force every one of them to fabricate an answer. Nothing outside this
 * module can name this type, so implementing it costs those doubles nothing.
 */
internal interface QuicheBackedConnection {
    val quicheDriver: QuicheDriver
}
