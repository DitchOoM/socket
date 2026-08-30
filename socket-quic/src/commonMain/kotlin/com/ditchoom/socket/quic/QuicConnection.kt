package com.ditchoom.socket.quic

import kotlinx.coroutines.flow.StateFlow

/**
 * An established QUIC connection — extends [QuicScope] with lifecycle management. This is the
 * SPI return type of [QuicEngine.connect]: a backend module (e.g. `socket-quic-quiche`)
 * implements it, and the [withQuicConnection] wrapper consumes it.
 *
 * Users normally never name this type — they interact via [QuicScope] inside [withQuicConnection]
 * or [QuicServer.connections] blocks, which own [close]. It is public only so the engine SPI can
 * cross the module boundary between an engine module and the default bundle.
 */
interface QuicConnection : QuicScope {
    /** Current connection state (used by the withQuicConnection/withQuicServer wrappers for lifecycle management). */
    val state: StateFlow<QuicConnectionState>

    /**
     * Which connection this is — the stable session id plus the current wire CID.
     *
     * Deliberately lives here rather than on [QuicConnectionState]: identity belongs to a connection
     * *instance*, while a state is a lifecycle *value* that is compared by equality (golden traces and
     * the fuzz determinism invariant both do exactly that). Folding an instance id into a value type
     * would make every state comparison identity-sensitive, and would force doubles with no real
     * connection to fabricate one.
     *
     * Required, with no default, for the reason Phase 3 made the migration capability required: a
     * backend that cannot answer should fail to compile rather than silently report nothing.
     */
    override val identity: QuicConnectionIdentity

    /**
     * What this connection's data plane requires of caller-supplied buffers — see
     * [QuicCapabilities.requiresNativeMemoryBuffers].
     *
     * Required, with no default, for the same reason as [identity]: a backend hands buffer addresses
     * to native code or it does not, and one that cannot say which should fail to compile rather than
     * inherit a claim. A double with no engine behind it answers [QuicCapabilities.None], which for it
     * is the truth.
     */
    override val capabilities: QuicCapabilities

    /**
     * What the network was doing, for correlating this connection against a
     * [com.ditchoom.socket.NetworkMonitor].
     *
     * Readable at any time. While the connection is live this reports the *current* observation — a
     * preview of what a close would record. Once the connection reaches
     * [QuicConnectionState.Closed] it is **frozen** at the value observed then, so reading it after
     * the fact still answers "what was the network doing when this died?" rather than "what is it
     * doing now?".
     *
     * Defaults to [NetworkAtClose.NotObserved] — the truthful answer for a connection nothing is
     * observing: a test double, or a **server-accepted** connection, which has no local client network
     * path to correlate against (the same reason automatic migration is client-only).
     *
     * A client connection resolves [QuicOptions.networkMonitor] **once**, at connect, and hands that one
     * instance to auto-migration, to the trace tap, and to this correlation. Sharing the instance is the
     * invariant, not an optimisation: a second monitor would report an `ObservationSequence` indexing a
     * different stream than the one that triggered the migration — two unrelated counters that look
     * joinable. A connection whose resolved monitor is [com.ditchoom.socket.NetworkMonitor.AlwaysAvailable]
     * reports [NetworkAtClose.NotObserved], which that monitor's own KDoc names as the honest case.
     */
    override val networkAtClose: NetworkAtClose get() = NetworkAtClose.NotObserved

    /**
     * Derived from [state]: every driver-backed connection publishes the handshake's negotiated
     * protocol in [QuicConnectionState.Established], so one default here covers all platforms.
     * Readable while the connection is established — which is always the case inside the
     * scope blocks where user code runs.
     */
    override val negotiatedAlpn: String
        get() =
            when (val s = state.value) {
                is QuicConnectionState.Established -> s.negotiatedAlpn
                else -> error("negotiatedAlpn is only available while the connection is established (state: $s)")
            }

    /** Close the connection with a QUIC error. Called by the scope when the block ends. */
    suspend fun close(error: QuicError = QuicError.NoError)

    /**
     * Application-coded connection close (RFC 9000 §19.19). Delegates to [close] with a
     * [QuicError.ApplicationError], which the driver maps to `quiche_conn_close(app = true, …)` so
     * [errorCode] travels on the wire. One default here covers every platform [QuicConnection].
     */
    override suspend fun closeWithError(errorCode: Long) = close(QuicError.ApplicationError(errorCode))
}
