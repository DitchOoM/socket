@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** Whether an ACK waits for the handlers of received data, as [RunningHandlers] publishes it. */
internal enum class HandlerAck { Sent, Waiting }

/** Whether the connection must be served now: the last running handler ended while an ACK waits for it. */
internal enum class ServeNow { No, Yes }

/**
 * The handlers of a connection's received data that run now: a caller resumed with data, from its resumption until it
 * suspends again or finishes. The connection counts a handler in when it resumes the caller; the caller's thread counts
 * it out. Lock-free: whoever counts the last handler out reads [ack] after, and the connection that starts a wait
 * publishes [ack] and reads the count after, so one of the two sees the other and the ACK never waits for nobody.
 */
@Published
internal class RunningHandlers {
    private val running = AtomicLong(0L)
    private val ack = AtomicReference(HandlerAck.Sent)

    /** Inside: a caller was resumed with data its handler now runs on. */
    fun began() {
        running.incrementAndFetch()
    }

    /** The handler's thread: the handler suspended or finished. */
    fun ended(): ServeNow =
        when {
            running.decrementAndFetch() != 0L -> ServeNow.No
            ack.load() == HandlerAck.Waiting -> ServeNow.Yes
            else -> ServeNow.No
        }

    fun running(): Long = running.load()

    /** Inside: an ACK waits from now; the count is read again after this. */
    fun waiting() = ack.store(HandlerAck.Waiting)

    fun sent() = ack.store(HandlerAck.Sent)
}

/** Whether a turn's ACK waits: for nothing, for the serve that ends this pass, or for running handlers. */
private sealed interface HandlerPhase {
    data object Open : HandlerPhase

    data object PassWaiting : HandlerPhase

    /** Held until [deadlineNanos] at most; [datagrams] arrived since it began. */
    class Held : HandlerPhase {
        var deadlineNanos = 0L
        var datagrams = 0
    }
}

/** How a wait for handlers ended. */
private enum class HandlerEnd { Replied, Done, Capped }

/** Whether a turn's news needs a write whatever the hold says. */
private enum class Must { No, Yes }

/**
 * The ACK of a turn that resumed a handler with received data waits while that handler runs, never on a timer of its
 * own: the reply it hands in carries it, and a handler that suspends or finishes without one sends it at once. A
 * handler still running at [AckRule.UntilHandlerDone.cap], within the advertised max_ack_delay, sends it then. With no
 * handler running, the ACK waits only for the rest of the loop pass, as [SamePassHold] does. Every [HoldGate] other
 * than [HoldGate.Open] sends it at once.
 */
@Confined
internal class HandlerHold(
    private val rule: AckRule.UntilHandlerDone,
    val handlers: RunningHandlers,
) {
    private val held = HandlerPhase.Held()
    private var phase: HandlerPhase = HandlerPhase.Open
    private var received = 0
    private var asking = 0
    private var must = Must.No
    private var seenHanded = 0L
    private var seenOutput = 0L
    private var stale = Stale.No

    /** Waits begun for handlers, and how they ended: a reply carried the ACK, the handler was done, or the cap. */
    var holds = 0L
        private set
    var replied = 0L
        private set
    var done = 0L
        private set
    var capped = 0L
        private set

    fun received() {
        received++
    }

    fun mustWrite() {
        must = Must.Yes
    }

    /** Before a serve's write, at [now], from the monotonic counts of data [handed] up and of [output] handed to quiche. */
    fun decide(
        now: Nanos,
        handed: Long,
        output: Long,
    ): HandlerAsk {
        val newlyHanded = handed != seenHanded
        val reply = output != seenOutput
        seenHanded = handed
        seenOutput = output
        val datagrams = received
        received = 0
        val forced = must
        must = Must.No
        when (val p = phase) {
            is HandlerPhase.Held -> {
                p.datagrams += datagrams
                when {
                    reply -> ended(HandlerEnd.Replied)
                    forced == Must.Yes || now.value >= p.deadlineNanos || p.datagrams > AckHold.HOLD_DATAGRAMS -> ended(HandlerEnd.Capped)
                    handlers.running() == 0L -> ended(HandlerEnd.Done)
                    else -> return HandlerAsk.Gate
                }
                return HandlerAsk.Write
            }
            is HandlerPhase.PassWaiting -> {
                phase = HandlerPhase.Open
                return HandlerAsk.Write
            }
            is HandlerPhase.Open -> Unit
        }
        if (reply || forced == Must.Yes || !newlyHanded || datagrams == 0 || datagrams > AckHold.HOLD_DATAGRAMS) return HandlerAsk.Write
        if (handlers.running() == 0L) {
            phase = HandlerPhase.PassWaiting
            return HandlerAsk.UntilPassEnds
        }
        asking = datagrams
        return HandlerAsk.Gate
    }

    /** After [decide] asked: whether the ACK waits for the running handlers at [now], given the connection's [gate]. */
    fun gated(
        now: Nanos,
        gate: HoldGate,
    ): AckStep {
        when (gate) {
            HoldGate.Open -> Unit
            HoldGate.Handshaking, HoldGate.Closing, HoldGate.PathMoving, HoldGate.Backlogged, HoldGate.LossRecent -> {
                when (phase) {
                    is HandlerPhase.Held -> ended(HandlerEnd.Capped)
                    is HandlerPhase.Open, is HandlerPhase.PassWaiting -> Unit
                }
                return AckStep.Write
            }
        }
        when (phase) {
            is HandlerPhase.Held -> return AckStep.UntilReply
            is HandlerPhase.Open, is HandlerPhase.PassWaiting -> Unit
        }
        if (rule.cap.nanos <= 0L) return AckStep.Write
        held.deadlineNanos = now.value + rule.cap.nanos
        held.datagrams = asking
        phase = held
        holds++
        handlers.waiting()
        // The last handler may have ended before the wait was published: it saw nothing to serve.
        if (handlers.running() == 0L) {
            ended(HandlerEnd.Done)
            return AckStep.Write
        }
        return AckStep.UntilReply
    }

    fun offerWake(sink: WakeSink) =
        when (val p = phase) {
            is HandlerPhase.Open, is HandlerPhase.PassWaiting -> Unit
            is HandlerPhase.Held -> sink.offer(Nanos(p.deadlineNanos))
        }

    fun deadlineLeft(): Stale = stale.also { stale = Stale.No }

    private fun ended(how: HandlerEnd) {
        phase = HandlerPhase.Open
        stale = Stale.Yes
        handlers.sent()
        when (how) {
            HandlerEnd.Replied -> replied++
            HandlerEnd.Done -> done++
            HandlerEnd.Capped -> capped++
        }
    }
}

/** What [HandlerHold.decide] answers: write now, wait for the end of the pass, or ask the gate whether to wait for handlers. */
internal enum class HandlerAsk { Write, UntilPassEnds, Gate }
