package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicSocketDrops
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.udp.LentDrop
import com.ditchoom.socket.quic.core.udp.LentDrops
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Whose socket a connection or a listener runs on, and so what its close waits for and its statistics count. */
internal sealed interface ChannelHold {
    data object Owned : ChannelHold

    /** A channel the caller lent, held by [claim]; what its io drops is counted in [drops]. */
    class Lent(
        val claim: LentClaim,
        val drops: LentDrops,
    ) : ChannelHold
}

/**
 * Waits until a lent channel is free again, so a connect or serve right after a close or an end succeeds. It waits for
 * the io's pump to end, so the next claimant never races a live reader; [LET_GO_BOUND] bounds it for a channel whose
 * own receive or send ignores cancellation, which then stays claimed.
 */
internal suspend fun ChannelHold.letGo() {
    when (this) {
        is ChannelHold.Owned -> Unit
        is ChannelHold.Lent -> withTimeoutOrNull(LET_GO_BOUND) { claim.awaitReleased() }
    }
}

/** The pump's drain bound and a second more for its receiver's cancel. */
private val LET_GO_BOUND = 2.seconds

/** These statistics with what a lent channel's io dropped. */
internal fun QuicStatsRead.withSocketDrops(hold: ChannelHold): QuicStatsRead =
    when (hold) {
        is ChannelHold.Owned -> this
        is ChannelHold.Lent ->
            when (this) {
                is QuicStatsRead.Read -> QuicStatsRead.Read(stats.withLent(hold.drops))
                is QuicStatsRead.Ended -> this
            }
    }

private fun QuicConnectionStats.withLent(drops: LentDrops) =
    QuicConnectionStats(
        packetsSent = packetsSent,
        packetsReceived = packetsReceived,
        packetsLost = packetsLost,
        packetsSpuriouslyLost = packetsSpuriouslyLost,
        packetsRetransmitted = packetsRetransmitted,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        bytesAcknowledged = bytesAcknowledged,
        bytesLost = bytesLost,
        streamBytesRetransmitted = streamBytesRetransmitted,
        datagramsSent = datagramsSent,
        datagramsReceived = datagramsReceived,
        paths = paths,
        activePath = activePath,
        datagramDrops = datagramDrops,
        socketDrops =
            QuicSocketDrops.LentChannel(
                foreignSource = drops.count(LentDrop.Foreign),
                arrivalsFull = drops.count(LentDrop.ArrivalsFull),
                tooLarge = drops.count(LentDrop.TooLarge),
                unaddressed = drops.count(LentDrop.Unaddressed),
                sendTooLarge = drops.count(LentDrop.SendTooLarge),
                sendUnreachable = drops.count(LentDrop.SendUnreachable),
                sendWouldBlock = drops.count(LentDrop.SendWouldBlock),
                sendRefused = drops.count(LentDrop.SendRefused),
            ),
    )
