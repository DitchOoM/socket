package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import kotlinx.coroutines.CompletableDeferred

/**
 * BoringSSL's `ssl_early_data_reason_t` values, as `quiche_conn_early_data_reason` reports them, and
 * their decoding into the public outcome types. The numbering is BoringSSL's (`include/openssl/ssl.h`).
 */
internal object QuicEarlyDataReason {
    const val DISABLED = 1
    const val ACCEPTED = 2
    const val PEER_DECLINED = 4
    const val NO_SESSION_OFFERED = 5
    const val SESSION_NOT_RESUMED = 6
    const val UNSUPPORTED_FOR_SESSION = 7
    const val HELLO_RETRY_REQUEST = 8
    const val ALPN_MISMATCH = 9
    const val TICKET_AGE_SKEW = 12
    const val QUIC_PARAMETER_MISMATCH = 13

    /** 0-RTT's fate on a resumed connection. */
    fun earlyData(reason: Int): QuicEarlyDataOutcome =
        when (reason) {
            ACCEPTED -> QuicEarlyDataOutcome.Accepted
            DISABLED -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.NotEnabled)
            PEER_DECLINED -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.DeclinedByPeer)
            UNSUPPORTED_FOR_SESSION -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.NotPermittedBySession)
            HELLO_RETRY_REQUEST -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.HelloRetryRequest)
            ALPN_MISMATCH -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.AlpnMismatch)
            TICKET_AGE_SKEW -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.TicketAgeSkew)
            QUIC_PARAMETER_MISMATCH -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.TransportParametersChanged)
            else -> QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.Unrecognized(reason))
        }

    /**
     * The handshake's resumption outcome for [role]. `is_resumed` is the one fact both ends always have.
     * For a full handshake a client knows what it [offer]ed; a server reads TLS's reason chain, which
     * stops at "0-RTT disabled" before it reaches whether a ticket was offered.
     */
    fun outcome(
        role: QuicRole,
        offer: SessionOffer,
        resumed: Boolean,
        reason: Int,
    ): QuicResumptionOutcome =
        if (resumed) {
            QuicResumptionOutcome.Resumed(earlyData(reason))
        } else {
            QuicResumptionOutcome.FullHandshake(
                when (role) {
                    QuicRole.Client ->
                        when (offer) {
                            SessionOffer.None -> QuicFullHandshakeReason.NoTicketOffered
                            is SessionOffer.Unusable -> QuicFullHandshakeReason.TicketUnusable(offer.quicheError)
                            is SessionOffer.Offered -> QuicFullHandshakeReason.TicketNotResumed
                        }
                    QuicRole.Server ->
                        when (reason) {
                            NO_SESSION_OFFERED -> QuicFullHandshakeReason.NoTicketOffered
                            SESSION_NOT_RESUMED -> QuicFullHandshakeReason.TicketNotResumed
                            else -> QuicFullHandshakeReason.NotReported
                        }
                },
            )
        }
}

/**
 * The session a client offered before its first flight. A server driver takes [None]: its handshake
 * answers the peer's offer, which TLS reports to it directly.
 */
sealed interface SessionOffer {
    /** When the first flight may leave. Only a session quiche took can carry 0-RTT, so only it can be held. */
    val firstFlight: FirstFlight

    /** No session: a client without a ticket, and every server. */
    data object None : SessionOffer {
        override val firstFlight = FirstFlight.Immediate
    }

    /** quiche refused the ticket (`quiche_conn_set_session` returned [quicheError]), so none was offered. */
    data class Unusable(
        val quicheError: Int,
    ) : SessionOffer {
        override val firstFlight = FirstFlight.Immediate
    }

    /** quiche took the ticket. */
    class Offered(
        override val firstFlight: FirstFlight,
    ) : SessionOffer
}

/** When a client driver may send its first flight. */
sealed interface FirstFlight {
    /** As soon as the driver starts. */
    data object Immediate : FirstFlight

    /**
     * After [released] completes, or earlier if a command needs the network: 0-RTT writes queued
     * before then leave in the first flight, alongside the ClientHello.
     */
    class HeldForEarlyData(
        val released: CompletableDeferred<Unit>,
    ) : FirstFlight
}

/**
 * Offer [resumption]'s ticket on [conn] — a client connection quiche has created and not yet sent or
 * received on — and say what was offered. Runs on the connect path before the driver starts, the only
 * moment `quiche_conn_set_session` is valid and nothing else can touch [conn].
 */
internal fun QuicheApi.offerSession(
    conn: QuicheConn,
    resumption: QuicResumption,
    bufferFactory: BufferFactory,
): SessionOffer =
    when (resumption) {
        QuicResumption.None -> SessionOffer.None
        is QuicResumption.Offer -> {
            val serialized = resumption.ticket.serialized()
            val native = bufferFactory.allocate(serialized.remaining())
            try {
                native.write(serialized)
                native.resetForRead()
                val rc = connSetSession(conn, native.driverOwnedNativeAddress(), native.remaining())
                if (rc < 0) {
                    SessionOffer.Unusable(rc)
                } else {
                    SessionOffer.Offered(
                        when (resumption.earlyData) {
                            QuicEarlyData.None -> FirstFlight.Immediate
                            is QuicEarlyData.Replayable -> FirstFlight.HeldForEarlyData(CompletableDeferred())
                        },
                    )
                }
            } finally {
                native.freeNativeMemory()
            }
        }
    }

/**
 * Run the caller's [QuicEarlyData.Replayable] block once, where [offer] says it belongs: before the
 * first flight when quiche took the ticket, so its writes can go as 0-RTT, and after [awaitEstablished]
 * otherwise, as ordinary data. [awaitEstablished] runs exactly once either way.
 */
internal suspend fun runEarlyData(
    resumption: QuicResumption,
    offer: SessionOffer,
    scope: QuicEarlyDataScope,
    awaitEstablished: suspend () -> Unit,
) {
    val early =
        when (resumption) {
            QuicResumption.None -> QuicEarlyData.None
            is QuicResumption.Offer -> resumption.earlyData
        }
    when (early) {
        QuicEarlyData.None -> awaitEstablished()
        is QuicEarlyData.Replayable ->
            when (val flight = offer.firstFlight) {
                is FirstFlight.HeldForEarlyData -> {
                    try {
                        scope.(early.write)()
                    } finally {
                        flight.released.complete(Unit)
                    }
                    awaitEstablished()
                }
                FirstFlight.Immediate -> {
                    awaitEstablished()
                    scope.(early.write)()
                }
            }
    }
}

/** Read [conn]'s serialized session into a [QuicSessionTicket]; [QuicSessionTicketState.NotIssued] when there is none. */
internal fun QuicheApi.readSessionTicket(
    conn: QuicheConn,
    bufferFactory: BufferFactory,
): QuicSessionTicketState {
    val length = connSession(conn, 0L, 0)
    if (length <= 0) return QuicSessionTicketState.NotIssued
    val native = bufferFactory.allocate(length)
    try {
        val copied = connSession(conn, native.driverOwnedNativeAddress(), length)
        if (copied != length) return QuicSessionTicketState.NotIssued
        native.setLimit(length)
        return QuicSessionTicketState.Issued(QuicSessionTicket.restore(native))
    } finally {
        native.freeNativeMemory()
    }
}

/** The part of a connection a [QuicEarlyData.Replayable] block sees. */
internal class ConnectionEarlyDataScope(
    private val connection: QuicScope,
) : QuicEarlyDataScope {
    override val bufferFactory: BufferFactory get() = connection.bufferFactory

    override suspend fun openStream(): QuicByteStream = connection.openStream()

    override suspend fun openUniStream(): QuicByteStream = connection.openUniStream()
}

/**
 * Install [keys] on a server's quiche [config]. [QuicSessionTicketKeys.PerServer] leaves BoringSSL's
 * own per-config key in place; one config serves every connection the server accepts, so its tickets
 * resume on any of them.
 */
internal fun QuicheApi.applySessionTicketKeys(
    config: QuicheConfig,
    keys: QuicSessionTicketKeys,
    bufferFactory: BufferFactory,
) {
    when (keys) {
        QuicSessionTicketKeys.PerServer -> Unit
        is QuicSessionTicketKeys.Shared -> {
            val material = keys.material()
            val native = bufferFactory.allocate(material.remaining())
            try {
                native.write(material)
                native.resetForRead()
                val rc = configSetTicketKey(config, native.driverOwnedNativeAddress(), native.remaining())
                if (rc < 0) throw SessionTicketKeyRefused(rc)
            } finally {
                native.freeNativeMemory()
            }
        }
    }
}

/** quiche refused session ticket key material of the one length it accepts; [quicheError] is its code. */
internal class SessionTicketKeyRefused(
    val quicheError: Int,
) : IllegalStateException("quiche refused the session ticket key (error $quicheError)")
