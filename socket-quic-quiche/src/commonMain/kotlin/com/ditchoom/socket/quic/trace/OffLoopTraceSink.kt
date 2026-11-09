@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.quic.serverReceiveDispatcher
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Hands every trace event to the application's [sink] **off the transport's threads**.
 *
 * A [TraceSink] is application code, and the transport never runs application code on its own threads.
 * On JVM/Android a connection's events are recorded on its event loop, which serves other connections
 * too. So [emit] only queues the event on [delivery], without blocking or suspending, and returns. The
 * queue's one delivery coroutine, on the application's dispatcher (`Dispatchers.IO` on JVM/Android),
 * calls each sink. A sink may block without stalling any connection.
 *
 * **Order.** Every capture of the process shares one queue ([TraceDeliveryQueue.Shared]), so events reach
 * their sinks in the order they were recorded, across connections as well as within one. A single sink
 * shared by several captures (the log-sink convenience, or a server's own capture beside its
 * connections') sees one interleaved, time-ordered record, as it did when sinks were called inline.
 *
 * **A sink that falls behind loses the oldest events, never the newest, and is told how many.** The
 * queue holds a bounded number of events. When it is full, a new event evicts the oldest one still
 * queued and counts it against that event's sink ([dropped]). Before that sink's next event, it receives
 * one [TraceEvent.Drop] of type [OVERFLOW_TYPE] saying how many were lost. The recent past is what
 * explains a failure, and a trace with a declared gap still reads correctly; an undeclared gap would not.
 */
internal class OffLoopTraceSink(
    private val sink: TraceSink,
    private val delivery: TraceDeliveryQueue = TraceDeliveryQueue.Shared,
) : TraceSink {
    private val droppedTotal = AtomicLong(0)
    private val droppedUnreported = AtomicLong(0)
    private val lastDroppedAt = AtomicLong(0)

    /** Events of this sink evicted unseen because delivery fell behind, since this sink was made. */
    val dropped: Long get() = droppedTotal.load()

    /** Queues [event] and returns at once: never blocks, never suspends, never calls [sink] here. */
    override fun emit(event: TraceEvent) = delivery.enqueue(this, event)

    internal fun evicted(event: TraceEvent) {
        droppedTotal.addAndFetch(1)
        droppedUnreported.addAndFetch(1)
        lastDroppedAt.store(event.at.inWholeNanoseconds)
    }

    /** Called by the delivery coroutine only. */
    internal fun deliver(event: TraceEvent) {
        val lost = droppedUnreported.exchange(0)
        if (lost > 0) {
            call(
                TraceEvent.Drop(
                    // When the last of them was recorded: the gap ends there.
                    at = lastDroppedAt.load().nanoseconds,
                    type = OVERFLOW_TYPE,
                    message = "$lost trace event(s) dropped: the sink fell behind and the oldest queued were evicted",
                ),
            )
        }
        call(event)
    }

    private fun call(event: TraceEvent) {
        try {
            sink.emit(event)
        } catch (e: Throwable) {
            // A sink that throws loses that event, not the connection or the events after it.
            if (e is CancellationException) throw e
        }
    }

    /** See [TraceDeliveryQueue.flush]. */
    suspend fun flush(bound: Duration = DEFAULT_FLUSH_BOUND): Boolean = delivery.flush(bound)

    companion object {
        /** How long a connection's end waits for its trace to reach a slow sink. */
        val DEFAULT_FLUSH_BOUND: Duration = 5.seconds

        /** The [TraceEvent.Drop.type] of the report that events were evicted unseen. */
        const val OVERFLOW_TYPE = "com.ditchoom.socket.quic.trace.OffLoopTraceSink.Overflow"
    }
}

/**
 * The queue [OffLoopTraceSink]s share, and the one coroutine on [dispatcher] that delivers it, which
 * runs only while events are queued. [capacity] events at most; past that the oldest is evicted.
 */
internal class TraceDeliveryQueue(
    dispatcher: CoroutineDispatcher,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private class Pending(
        val port: OffLoopTraceSink,
        val event: TraceEvent,
    )

    /** Events queued so far, and events delivered or evicted so far: [flush] waits for the second to catch up. */
    private val queued = AtomicLong(0)
    private val settled = MutableStateFlow(0L)

    private val queue =
        Channel<Pending>(capacity, BufferOverflow.DROP_OLDEST) { evicted ->
            evicted.port.evicted(evicted.event)
            settled.update { it + 1 }
        }

    private val delivering = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("quic-trace-delivery"))

    fun enqueue(
        port: OffLoopTraceSink,
        event: TraceEvent,
    ) {
        queued.addAndFetch(1)
        queue.trySend(Pending(port, event))
        startDelivering()
    }

    private fun startDelivering() {
        if (!delivering.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            while (true) {
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    next.port.deliver(next.event)
                    settled.update { it + 1 }
                }
                delivering.store(false)
                // An enqueue that found `delivering` set just before it was cleared has queued an event
                // and launched nothing: look once more, and carry on only if this coroutine wins again.
                if (queue.isEmpty || !delivering.compareAndSet(expectedValue = false, newValue = true)) return@launch
            }
        }
    }

    /**
     * Suspends until every event queued before this call has been delivered (or evicted), for at most
     * [bound]. Never blocks: it is a suspension, so a transport loop that calls it keeps serving its
     * other connections. A connection's driver calls it as it ends, so a trace read after `close()`
     * returns holds the whole connection. A sink stuck for longer than [bound] does not hold the close.
     *
     * @return whether everything was delivered within [bound].
     */
    suspend fun flush(bound: Duration): Boolean {
        val target = queued.load()
        return withTimeoutOrNull(bound) { settled.first { it >= target } } != null
    }

    companion object {
        /** Events queued, across every capture of the process, before the oldest are evicted. */
        const val DEFAULT_CAPACITY = 65_536

        /** The process-wide queue every production capture delivers through. */
        val Shared: TraceDeliveryQueue by lazy { TraceDeliveryQueue(serverReceiveDispatcher) }
    }
}
