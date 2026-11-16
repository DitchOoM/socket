package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.RttSample

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
)

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
    fun established(report: ConnectReport)

    /** The peer opened [stream]. */
    fun incoming(stream: QuicStreamId)

    fun ended(end: ConnEnd)
}
