package com.ditchoom.socket.quic

/**
 * A self-contained post-mortem for one connection: *which* it was, *why* it ended, and *what the
 * network was doing* — everything needed to explain a close without still holding the connection.
 *
 * ## Why this is assembled rather than carried on the state
 * [QuicConnectionState.Closed] carries only [QuicCloseReason], on purpose. A state is a value compared
 * by equality — golden trace fixtures and the fuzz determinism invariant both do that — so putting a
 * per-instance connection id inside it would make every state comparison identity-sensitive, and would
 * force test doubles with no real connection to invent an id in order to construct a state at all.
 *
 * Keeping *why* on the state and *which* on the connection lets each be used alone, and this type
 * exists for the case where you want them together: a log line, a telemetry record, a crash report —
 * anywhere the close outlives the object.
 *
 * ```kotlin
 * connection.state
 *     .filterIsInstance<QuicConnectionState.Closed>()
 *     .onEach { log.warn("quic closed: ${connection.closeContext(it.reason)}") }
 * ```
 */
data class QuicCloseContext(
    val identity: QuicConnectionIdentity,
    val reason: QuicCloseReason,
    val network: NetworkAtClose,
) {
    /**
     * Rendered for a log line, which is what this type is overwhelmingly used for. Names the session id
     * (the one that survives migration and so identifies the connection across a reconnect cycle), the
     * wire CID, the reason, and the network correlation.
     */
    override fun toString(): String = "session=${identity.session} wire=${identity.wire} reason=$reason $network"
}

/**
 * Assemble the post-mortem for [reason] from this connection's identity and its network correlation.
 *
 * Takes the reason as a parameter rather than reading [QuicConnection.state] so it is usable from a
 * state collector that already has the [QuicConnectionState.Closed] in hand, with no second read that
 * could observe a different value.
 */
fun QuicConnection.closeContext(reason: QuicCloseReason): QuicCloseContext =
    QuicCloseContext(identity = identity, reason = reason, network = networkAtClose)
