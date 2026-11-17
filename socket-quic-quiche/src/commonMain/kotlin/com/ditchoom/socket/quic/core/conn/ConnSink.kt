package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.RttSample
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** What an established connection reports once: how its handshake went, and the length of the negotiated ALPN. */
internal class ConnectReport(
    val handshakeNanos: Long,
    val alpnLength: Int,
    val firstSample: RttSample,
    /** The RTT assumed before the first sample. */
    val priorNanos: Long,
    val link: LinkClass,
    /** quiche's PTO expiries during the handshake. */
    val probes: Int,
    val pace: ConnectPace,
    /** How the handshake used a session ticket. */
    val resumption: QuicResumptionOutcome,
)

/** A server accepted the client's 0-RTT before its handshake completed, which had settled the ALPN and the resumption. */
internal class EarlyReport(
    val alpnLength: Int,
    val resumption: QuicResumptionOutcome,
)

/** Whether a stream the peer opened arrived before the handshake completed, so what it carries can be a 0-RTT replay. */
internal enum class Arrival { Early, Confirmed }

/** Whether a handshake took as long as its prior and its RTT sample say it should. */
internal sealed interface ConnectPace {
    data object AsExpected : ConnectPace

    class Slow(
        val why: SlowConnect,
    ) : ConnectPace
}

/**
 * Why a handshake was slow: the first sample exceeded three times the prior, a probe was sent, or the handshake took
 * more than three round trips of the sampled RTT.
 */
internal enum class SlowConnect { RttAbovePrior, ProbesNeeded, PeerSlowToAnswer }

/** The one upward notification of a connection. Called inside it, on the thread inside. */
internal interface ConnSink {
    /** The handshake completed; the connection already holds everything it keeps, its spare ids handed out. */
    fun established(report: ConnectReport)

    /** A server accepted the client's 0-RTT; the handshake has not completed. At most once, before [established]. */
    fun earlyData(report: EarlyReport)

    /** The peer opened [stream]. */
    fun incoming(
        stream: QuicStreamId,
        arrival: Arrival,
    )

    /** A client's server issued a session ticket: its [length] serialized bytes at [bytes], valid during the call. Once. */
    fun ticket(
        bytes: NativeBytes,
        length: Int,
    )

    fun ended(end: ConnEnd)
}
