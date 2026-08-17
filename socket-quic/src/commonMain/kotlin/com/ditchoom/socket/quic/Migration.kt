package com.ditchoom.socket.quic

/**
 * Where a [QuicScope.migrate] call should move the connection's local path (RFC 9000 §9).
 *
 * Replaces the `(localHost: String?, localPort: Int)` pair, in which `null` and `0` were sentinels for
 * "the default interface" and "any ephemeral port" — two nullable/zero-valued knobs that could be
 * combined into requests no platform serves, and that read as "bind nowhere on port zero" to anyone who
 * had not memorised the convention.
 */
sealed interface MigrationTarget {
    /**
     * A fresh, platform-chosen local endpoint on the current default interface. The only request every
     * platform can serve — Network.framework assigns the endpoint itself, so a named endpoint is not
     * bindable on Apple at all — and what automatic migration always issues.
     */
    data object FreshLocalEndpoint : MigrationTarget

    /** A fresh ephemeral port on the local address [host]. */
    data class LocalAddress(
        val host: String,
    ) : MigrationTarget {
        init {
            require(host.isNotBlank()) { "host must not be blank" }
        }
    }

    /** Exactly [host]:[port]. */
    data class LocalEndpoint(
        val host: String,
        val port: Int,
    ) : MigrationTarget {
        init {
            require(host.isNotBlank()) { "host must not be blank" }
            require(port in 1..65535) { "port must be in 1..65535; use LocalAddress for an ephemeral port" }
        }
    }
}

/**
 * A local UDP endpoint as the platform **resolved** it — never as it was requested.
 *
 * The distinction is the whole point. `migrate()` used to answer `Succeeded(localHost, localPort)` by
 * echoing back the arguments it was handed, so an automatic migration — which always asks for
 * "anywhere, any port" — reported `Succeeded(null, 0)`: a success value that named no endpoint at all,
 * on a platform where the endpoint is precisely the thing the caller cannot otherwise learn. Every
 * backend already resolves the socket's real local sockaddr (it has to: quiche probes and migrates onto
 * that 4-tuple), so this stops discarding it.
 *
 * There is deliberately **no** `Unreported`/`Unknown` case: all four backends can answer, and a variant
 * no backend can produce is worse than absent.
 */
data class QuicLocalEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535, was $port" }
    }

    override fun toString(): String = "$host:$port"
}

/**
 * Result of a [QuicScope.migrate] call.
 *
 * Three levels deep so a consumer can dispatch at whichever depth it needs: the automatic-migration
 * reactor `when`s over the three top-level cases (moved / never will / not this time), while telemetry
 * `when`s over the leaves. Every leaf here was one of the driver's `Failed("…")` prose strings — the
 * outcomes are not new, only their type is.
 */
sealed interface MigrationResult {
    /**
     * The connection's active path is now [localEndpoint] — the endpoint the platform **bound**, not the
     * one asked for. On [MigrationTarget.FreshLocalEndpoint] (what automatic migration issues) this is
     * the only way to learn where the connection landed.
     */
    data class Succeeded(
        val localEndpoint: QuicLocalEndpoint,
    ) : MigrationResult

    /** The active path did not move. */
    sealed interface Unmoved : MigrationResult {
        /**
         * …and never will while this connection is open. Every later call answers the same, whatever the
         * network does and whichever [MigrationTarget] you name. This is the family an automatic reactor
         * stops on.
         */
        sealed interface Impossible : Unmoved {
            /** Only clients migrate in QUIC v1 (RFC 9000 §9); this is a server-accepted connection. */
            data object ServerConnection : Impossible

            /** [MigrationPolicy.Forbidden] — this endpoint advertised `disable_active_migration`. */
            data object PolicyForbids : Impossible

            /**
             * The **peer** advertised `disable_active_migration` (RFC 9000 §18.2), so it will not accept
             * traffic from a new local address. Read from `quiche_conn_peer_transport_params`, which is
             * why this is a knowable state rather than a probe that mysteriously fails validation.
             *
             * Every backend binds that accessor, so this is never a guess and never degrades to
             * [Failed.PathNotValidated]. Distinguish it from [Failed.HandshakeNotConfirmed], which is the
             * *other* answer that read can give: there the parameters have not arrived yet, and a later
             * attempt may well succeed.
             */
            data object PeerForbids : Impossible

            /** This backend cannot open a second local path (no `UdpChannelFactory`; a non-quiche engine). */
            data object BackendCannotMigrate : Impossible

            /** The connection closed before the migration could complete. */
            data object ConnectionClosed : Impossible
        }

        /** …this time. A later attempt, or a different target, may move it. */
        sealed interface Failed : Unmoved {
            /**
             * The handshake has not confirmed yet, so the peer's transport parameters are unknown and
             * RFC 9000 §9 forbids initiating migration: *"An endpoint MUST NOT initiate connection
             * migration before the handshake is confirmed."*
             *
             * Emphatically **not** [Impossible.PeerForbids]. Both come from the same
             * `quiche_conn_peer_transport_params` read, but this one is the accessor saying *not yet* —
             * it resolves on its own as the handshake completes, and the next attempt may succeed. That
             * distinction is the whole reason the backend accessor returns a three-state type instead of
             * a `Boolean?`: a nullable would have made "the peer said no" and "nobody has said anything
             * yet" share one token.
             */
            data object HandshakeNotConfirmed : Failed

            /**
             * The platform assigns the local endpoint itself, so the named target cannot be bound.
             * [MigrationTarget.FreshLocalEndpoint] is served everywhere.
             */
            data object EndpointNotSelectable : Failed

            /** Another migration is already probing — one path move at a time. */
            data object AlreadyInProgress : Failed

            /** The peer has issued no spare connection id, so there is nothing to migrate onto (RFC 9000 §9.5). */
            data object NoSpareConnectionId : Failed

            /** The new local socket could not be opened. */
            data class LocalPathUnavailable(
                val cause: Throwable,
            ) : Failed

            /** quiche refused to probe the new path. */
            data class ProbeRejected(
                val code: Int,
            ) : Failed

            /** The peer never validated the new path (PATH_CHALLENGE unanswered, or quiche failed it). */
            data object PathNotValidated : Failed

            /** The path validated but quiche refused to switch the active path onto it. */
            data class SwitchRejected(
                val code: Int,
            ) : Failed
        }
    }
}

/**
 * Where a connection's local path currently stands, surfaced via [QuicScope.pathState].
 *
 * Replaces `PathInfo(phase, localHost, localPort)`, whose host/port echoed the *request* and were
 * meaningless in the `None`/`Failed` phases — a triple in which two thirds were routinely noise. Here
 * each case carries exactly what that case knows, and the endpoints are the platform's resolved ones.
 */
sealed interface QuicPathState {
    /** On the local path the connection opened with; no migration has been requested. */
    data object Original : QuicPathState

    /** A new local path is open at [endpoint] and a PATH_CHALLENGE is in flight. */
    data class Probing(
        val endpoint: QuicLocalEndpoint,
    ) : QuicPathState

    /** [endpoint] passed validation; the active path has not switched onto it yet. */
    data class Validated(
        val endpoint: QuicLocalEndpoint,
    ) : QuicPathState

    /** The active path is now [endpoint]. */
    data class Migrated(
        val endpoint: QuicLocalEndpoint,
    ) : QuicPathState

    /**
     * The last attempt did not move the path; the connection stayed where it was.
     *
     * Carries the typed [result] rather than restating the reason — one fact, one place. Typed
     * [MigrationResult.Unmoved] (not [MigrationResult]) so `Failed(Succeeded(…))` is unrepresentable;
     * that is the reason [MigrationResult.Unmoved] exists as a named middle layer.
     */
    data class Failed(
        val result: MigrationResult.Unmoved,
    ) : QuicPathState
}
