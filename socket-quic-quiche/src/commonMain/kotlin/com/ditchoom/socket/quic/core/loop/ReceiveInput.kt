package com.ditchoom.socket.quic.core.loop

/**
 * Whether a socket may hold datagrams: set by readiness, cleared by a receive that found none. [Backlog] is input the
 * poller cannot see (whole messages the io holds): the loop must not sleep while a socket is in it.
 */
internal enum class ReceiveInput { Maybe, Drained, Backlog }

/** When an endpoint reads its socket. */
internal enum class Readiness {
    /** When the poller reported it readable, and until a read finds nothing. */
    Polled,

    /** Every pass: a socket whose readiness no poller sees, such as one driven by a test on virtual time. */
    EveryPass,
}
