package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.NO_TIMEOUT_NANOS
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlinx.coroutines.Runnable

private sealed interface Armed {
    data object Not : Armed

    class At(
        val node: DeadlineNode,
    ) : Armed
}

/**
 * The one deadline of a connection: the earliest of quiche's timeout and every time the connection's owners offered
 * into [sink]. A deadline still armed for the same time or earlier is kept (it fires early, quiche ignores a timer
 * that has not expired, and the connection re-arms then), so a connection under traffic arms a new deadline only when
 * its time moved earlier.
 */
@Confined
internal class ConnTimer(
    private val q: QuicheConnRef,
    private val port: LoopPort,
    val sink: WakeSink,
) {
    private var armed: Armed = Armed.Not

    /** Re-arms after a turn at [now]; [fire] runs on the loop when the deadline falls due. */
    fun rearm(
        now: Nanos,
        fire: Runnable,
    ) {
        val wait = q.api.connTimeoutNanos(q.handle)
        val asked = sink.state()
        val offered = sink.earliest()
        sink.reset()
        val due =
            when (asked) {
                WakeState.Asked -> if (wait == NO_TIMEOUT_NANOS || offered.value < now.value + wait) offered else now + wait
                WakeState.NotAsked -> if (wait == NO_TIMEOUT_NANOS) return else now + wait
            }
        when (val a = armed) {
            is Armed.At -> if (a.node.isArmed() && a.node.dueNanos <= due.value) return
            is Armed.Not -> Unit
        }
        disarm()
        armed = Armed.At(port.arm(due, fire))
    }

    /** The deadline fell due: quiche's timeout runs. */
    fun expire() {
        when (val a = armed) {
            is Armed.At -> if (!a.node.isArmed()) armed = Armed.Not
            is Armed.Not -> Unit
        }
        q.api.connOnTimeout(q.handle)
    }

    /** Has quiche send an ack-eliciting packet at its next write, instead of waiting out a backed-off timer. */
    fun nudge() {
        q.api.connSendAckEliciting(q.handle)
    }

    fun disarm() {
        when (val a = armed) {
            is Armed.At -> a.node.disarm()
            is Armed.Not -> Unit
        }
        armed = Armed.Not
    }
}
