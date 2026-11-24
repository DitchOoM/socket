package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.NO_TIMEOUT_NANOS
import com.ditchoom.socket.quic.Rearmable
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.TimerCounts
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlinx.coroutines.Runnable
import kotlin.time.Duration.Companion.nanoseconds

/** Whether quiche's own timer has fallen due. */
internal enum class TimerDue { Due, NotYet }

/** The connection's deadline: made by the first arming, with the action every arming runs, and re-armed in place after. */
private sealed interface TimerDeadline {
    data object Unmade : TimerDeadline

    class Made(
        val deadline: Rearmable,
    ) : TimerDeadline
}

/**
 * The one deadline of a connection: the earliest of quiche's timeout and every time the connection's owners offered
 * into [sink]. A deadline still armed for the same time or earlier is kept (it fires early, quiche ignores a timer
 * that has not expired, and the connection re-arms then), so a connection under traffic arms a new deadline only when
 * its time moved earlier. The deadline is one [Rearmable], so re-arming it allocates nothing.
 */
@Confined
internal class ConnTimer(
    private val q: QuicheConnRef,
    private val port: LoopPort,
    val sink: WakeSink,
    private val counts: TimerCounts = TimerCounts(),
) {
    private var deadline: TimerDeadline = TimerDeadline.Unmade

    /** Re-arms after a turn at [now]; [fire] runs on the loop when the deadline falls due. */
    fun rearm(
        now: Nanos,
        fire: Runnable,
    ) {
        counts.rearmed()
        val wait = q.api.connTimeoutNanos(q.handle)
        val asked = sink.state()
        val offered = sink.earliest()
        sink.reset()
        val due =
            when (asked) {
                WakeState.Asked -> if (wait == NO_TIMEOUT_NANOS || offered.value < now.value + wait) offered else now + wait
                WakeState.NotAsked -> if (wait == NO_TIMEOUT_NANOS) return else now + wait
            }
        val made = made(fire)
        if (made.wanted().fold(none = { false }, at = { it <= due.value })) return
        counts.moved()
        port.arm(made, due)
    }

    /** Arms for the earliest time offered into [sink] alone, or not at all: an ended connection drives no quiche timer. */
    fun armOffered(fire: Runnable) {
        val asked = sink.state()
        val due = sink.earliest()
        sink.reset()
        when (asked) {
            WakeState.NotAsked -> return disarm()
            WakeState.Asked -> Unit
        }
        val made = made(fire)
        if (made.wanted().fold(none = { false }, at = { it == due.value })) return
        counts.moved()
        port.arm(made, due)
    }

    /** The deadline, made on the first arming with [fire]: a connection's every arming passes the same action. */
    private fun made(fire: Runnable): Rearmable =
        when (val d = deadline) {
            is TimerDeadline.Made -> d.deadline
            is TimerDeadline.Unmade -> port.rearmable { fire.run() }.also { deadline = TimerDeadline.Made(it) }
        }

    /** When quiche's draining ends, seen at [now]: its timer once it sent a close, or [now] when it has none. */
    fun drainEnds(now: Nanos): Nanos {
        val wait = q.api.connTimeoutNanos(q.handle)
        return if (wait == NO_TIMEOUT_NANOS) now else now + wait
    }

    /** Whether quiche's own timer is due: a deadline that fell due for another owner leaves quiche nothing to do. */
    fun quicheDue(): TimerDue {
        val wait = q.api.connTimeoutNanos(q.handle)
        return if (wait != NO_TIMEOUT_NANOS && wait <= 0L) TimerDue.Due else TimerDue.NotYet
    }

    /** The deadline fell due: quiche's timeout runs. */
    fun expire() {
        q.api.connOnTimeout(q.handle)
    }

    /**
     * The network is usable again: quiche sends what its next PTO would at its next write, past a congestion window
     * the outage's own probes filled, so the data lost in the outage leaves now and not a backed-off timer later.
     */
    fun nudge() {
        q.api.connProbeNow(q.handle)
    }

    fun disarm() {
        when (val d = deadline) {
            is TimerDeadline.Made -> d.deadline.disarm()
            is TimerDeadline.Unmade -> Unit
        }
    }

    /** What quiche and the loop deadline wait for, seen at [now]. */
    fun quicheWait(): QuicheWait =
        when (val wait = q.api.connTimeoutNanos(q.handle)) {
            NO_TIMEOUT_NANOS -> QuicheWait.Unarmed
            else -> QuicheWait.Due(wait.nanoseconds)
        }

    fun armed(now: Nanos): ArmedDeadline =
        when (val d = deadline) {
            is TimerDeadline.Unmade -> ArmedDeadline.Unarmed
            is TimerDeadline.Made ->
                d.deadline.wanted().fold(none = {
                    ArmedDeadline.Unarmed
                }, at = { ArmedDeadline.Due((it - now.value).nanoseconds) })
        }
}
