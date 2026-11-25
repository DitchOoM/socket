package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

private enum class KeepPhase { Handshaking, Established }

/**
 * Keeps an idle connection alive: once established, after the interval with nothing received or sent, quiche is
 * asked for an ack-eliciting packet, which the pass's write sends. The wake it offers goes into the connection's one
 * deadline, so no timer is made per ping.
 */
@Confined
internal class KeepAliveTimer(
    private val q: QuicheConnRef,
    private val intervalNanos: Long,
) {
    private var phase = KeepPhase.Handshaking
    private var lastActivity = 0L

    /** Monotonic: the pings asked of quiche. */
    var pinged = 0L
        private set

    /** The connection received or sent something at [now]. */
    fun touched(now: Nanos) {
        lastActivity = now.value
    }

    /** An established connection's turn at [now]: the first starts the idle clock; later ones ping after an idle interval. */
    fun step(now: Nanos) {
        when (phase) {
            KeepPhase.Handshaking -> {
                phase = KeepPhase.Established
                lastActivity = now.value
            }
            KeepPhase.Established ->
                if (now.value - lastActivity >= intervalNanos) {
                    q.api.connSendAckEliciting(q.handle)
                    pinged++
                    lastActivity = now.value
                }
        }
    }

    /** Offers when the next ping is due. */
    fun offerWake(sink: WakeSink) {
        when (phase) {
            KeepPhase.Handshaking -> Unit
            KeepPhase.Established -> sink.offer(Nanos(lastActivity + intervalNanos))
        }
    }
}

/** Inside, after a write: a write that sent anything is activity. */
internal fun ConnParts.wrote(now: Nanos) =
    when (egress.last()) {
        LastOut.None -> Unit
        LastOut.Kept, LastOut.Dropped -> slots.keepAlive.touched(now)
    }

internal fun KeepAlive.touched(now: Nanos) =
    when (this) {
        is KeepAlive.Off -> Unit
        is KeepAlive.Every -> timer.touched(now)
    }

internal fun KeepAlive.step(now: Nanos) =
    when (this) {
        is KeepAlive.Off -> Unit
        is KeepAlive.Every -> timer.step(now)
    }

internal fun KeepAlive.offerWake(sink: WakeSink) =
    when (this) {
        is KeepAlive.Off -> Unit
        is KeepAlive.Every -> timer.offerWake(sink)
    }

/** Monotonic: the keep-alive pings asked of quiche. */
internal fun KeepAlive.pinged(): Long =
    when (this) {
        is KeepAlive.Off -> 0L
        is KeepAlive.Every -> timer.pinged
    }
