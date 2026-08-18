package com.ditchoom.socket.quic

/**
 * Which connection this is — two identifiers, deliberately of **different types**.
 *
 * ## Why two, and why they must not be one value
 * QUIC connection IDs rotate. RFC 9000 §5.1 has endpoints issue and retire them over a connection's
 * life, and §9.5 makes migration use a *fresh* one on purpose — that is the privacy mechanism, not an
 * implementation detail. So "the connection ID" is not a thing that exists. A single field claiming to
 * be it would be correct at connect and silently wrong from the first migration onward, which is
 * precisely when someone is reading it.
 *
 * [session] and [wire] are therefore distinct types rather than two properties of one type. You cannot
 * pass one where the other belongs, and a log line naming `session` cannot drift into naming `wire`
 * because a refactor moved a field. The distinction lives in the type system rather than in a comment
 * somebody has to read.
 *
 * ## Which to use
 * - Following **one connection over time** — a log line, a telemetry record, correlating a reconnect
 *   cycle, telling three concurrent clients apart: use [session]. It survives migration, which is the
 *   whole point.
 * - Matching a **packet capture or a peer's log** at some instant: use [wire]. It is what is actually
 *   on the wire right now, and it will not match what you captured five minutes ago.
 */
data class QuicConnectionIdentity(
    val session: QuicSessionId,
    val wire: QuicWireConnectionId,
)

/**
 * A stable identifier for one connection *session*: the source connection ID this endpoint chose at
 * connect, fixed for the connection's whole life.
 *
 * This is **not** guaranteed to be on the wire — after a migration or a CID rotation it will not be —
 * so do not use it to match a packet capture. Use it for the question it answers: *which connection is
 * this?* It is the identifier that makes a reconnect cycle followable and that tells concurrent
 * connections apart in a log, which is exactly what a per-connection churn investigation needs.
 */
data class QuicSessionId(
    val hex: String,
) {
    override fun toString(): String = hex
}

/**
 * The connection ID currently in use on the wire.
 *
 * **This changes.** Read it when you need it; do not cache it and expect it to keep matching. A
 * migration issues a fresh CID by design (RFC 9000 §9.5), so a value read before a handoff will not
 * match packets sent after one.
 *
 * Sealed rather than nullable because "this backend cannot report it" and "there is no connection ID"
 * are different statements, and only one of them is ever true. A backend that has not bound quiche's
 * current-source-CID accessor reports [Unavailable] rather than a fabricated or empty value.
 */
sealed interface QuicWireConnectionId {
    /** The CID quiche reports as currently active for this connection. */
    data class Known(
        val hex: String,
    ) : QuicWireConnectionId {
        override fun toString(): String = hex
    }

    /**
     * This backend does not expose quiche's current source CID, so the wire identifier is genuinely
     * unknown here — not absent, and not empty. [QuicSessionId] is still available and still answers
     * "which connection is this?"; only wire-level correlation is unavailable.
     */
    data object Unavailable : QuicWireConnectionId {
        override fun toString(): String = "unavailable"
    }
}
