package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos

/** Whether loss was seen lately: quiet, or lossy until a time. */
private sealed interface LossMemory {
    data object Quiet : LossMemory

    class Lossy : LossMemory {
        var untilNanos = 0L
    }
}

/** Whether the connection saw loss lately, as [AckHoldReads.loss] answers. */
internal enum class LossSeen { Quiet, Recent }

/**
 * What the ACK hold reads from quiche: the smoothed RTT of the active path, and loss. Loss is quiche's count of packets
 * lost or resent and of packets received after a gap (RFC 9000 §13.2.1's immediate-ACK trigger): a count that grew
 * since the last reading makes loss recent for [LOSS_MEMORY_RTTS] smoothed RTTs.
 */
@Confined
internal class AckHoldReads(
    private val q: QuicheConnRef,
) {
    private val lossy = LossMemory.Lossy()
    private var memory: LossMemory = LossMemory.Quiet
    private var lost = 0L
    private var gaps = 0L

    fun smoothedRttNanos(): Long = q.api.connActivePathRttNanos(q.handle)

    fun loss(now: Nanos): LossSeen {
        val lostNow = q.api.connLossEvidence(q.handle)
        val gapsNow = q.api.connRecvGaps(q.handle)
        if (lostNow != lost || gapsNow != gaps) {
            lost = lostNow
            gaps = gapsNow
            lossy.untilNanos = now.value + LOSS_MEMORY_RTTS * maxOf(smoothedRttNanos(), 0L)
            memory = lossy
        }
        return when (val m = memory) {
            is LossMemory.Quiet -> LossSeen.Quiet
            is LossMemory.Lossy ->
                if (now.value < m.untilNanos) {
                    LossSeen.Recent
                } else {
                    memory = LossMemory.Quiet
                    LossSeen.Quiet
                }
        }
    }

    private companion object {
        const val LOSS_MEMORY_RTTS = 4L
    }
}
