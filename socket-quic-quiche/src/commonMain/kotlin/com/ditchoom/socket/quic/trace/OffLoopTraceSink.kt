@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.trace

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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Hands every trace event to the application's [sink] **off the transport's threads**.
 *
 * A [TraceSink] is application code, and the transport never runs application code on its own threads.
 * On JVM/Android a connection's events are recorded on its event loop, which serves other connections
 * too. So [emit] only queues the event, without blocking or suspending, and returns. A coroutine on
 * [dispatcher] (`Dispatchers.IO` on JVM/Android) delivers the events to [sink], one at a time and in the
 * order they were recorded. A sink may block without stalling any connection.
 *
 * **A sink that falls behind loses the oldest events, never the newest, and is told how many.** The
 * queue holds [capacity] events. When it is full, a new event evicts the oldest one still queued and
 * counts it ([dropped]). Before the next event it delivers, the sink receives one
 * [TraceEvent.Drop] of type [OVERFLOW_TYPE] that says how many events were lost since the last such
 * report. The recent past is what explains a failure, and a trace with a declared gap still reads
 * correctly; a gap nobody declared would not.
 *
 * At most one delivery coroutine runs per sink, and only while events are queued. There is no thread or
 * coroutine to close when the connection ends.
 */
internal class OffLoopTraceSink(
    private val sink: TraceSink,
    private val dispatcher: CoroutineDispatcher,
    private val capacity: Int = DEFAULT_CAPACITY,
) : TraceSink {
    private val droppedTotal = AtomicLong(0)
    private val droppedUnreported = AtomicLong(0)
    private val lastDroppedAt = AtomicLong(0)

    private val queue =
        Channel<TraceEvent>(capacity, BufferOverflow.DROP_OLDEST) { evicted ->
            droppedTotal.addAndFetch(1)
            settled.update { it + 1 }
            droppedUnreported.addAndFetch(1)
            lastDroppedAt.store(evicted.at.inWholeNanoseconds)
        }

    private val delivering = AtomicBoolean(false)

    /** Events queued so far, and events delivered or evicted so far: [flush] waits for the second to catch up. */
    private val queued = AtomicLong(0)
    private val settled = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("quic-trace-sink"))

    /** Events evicted unseen because the sink fell behind, since this sink was made. */
    val dropped: Long get() = droppedTotal.load()

    /** Queues [event] and returns at once: never blocks, never suspends, never calls [sink] here. */
    override fun emit(event: TraceEvent) {
        queued.addAndFetch(1)
        queue.trySend(event)
        startDelivering()
    }

    private fun startDelivering() {
        if (!delivering.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            while (true) {
                deliverQueued()
                delivering.store(false)
                // An emit that found `delivering` set just before it was cleared has queued an event
                // and launched nothing: look once more, and carry on only if this coroutine wins again.
                if (queue.isEmpty || !delivering.compareAndSet(expectedValue = false, newValue = true)) return@launch
            }
        }
    }

    private fun deliverQueued() {
        while (true) {
            val event = queue.tryReceive().getOrNull() ?: return
            val lost = droppedUnreported.exchange(0)
            if (lost > 0) {
                deliver(
                    TraceEvent.Drop(
                        // When the last of them was recorded: the gap ends there.
                        at = lastDroppedAt.load().nanoseconds,
                        type = OVERFLOW_TYPE,
                        message = "$lost trace event(s) dropped: the sink fell behind and the oldest queued were evicted",
                    ),
                )
            }
            deliver(event)
            settled.update { it + 1 }
        }
    }

    /**
     * Suspends until every event queued before this call has reached [sink] (or was evicted), for at most
     * [bound]. Never blocks: it is a suspension, so a transport loop that calls it keeps serving its
     * other connections. A connection's driver calls it as it ends, so a trace read after `close()`
     * returns holds the whole connection. A sink stuck for longer than [bound] does not hold the close.
     *
     * @return whether everything was delivered within [bound].
     */
    suspend fun flush(bound: Duration = DEFAULT_FLUSH_BOUND): Boolean {
        val target = queued.load()
        return withTimeoutOrNull(bound) { settled.first { it >= target } } != null
    }

    private fun deliver(event: TraceEvent) {
        try {
            sink.emit(event)
        } catch (e: Throwable) {
            // A sink that throws loses that event, not the connection or the events after it.
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        }
    }

    companion object {
        /** Events queued per sink before the oldest are evicted. */
        const val DEFAULT_CAPACITY = 8192

        /** How long a connection's end waits for its trace to reach a slow sink. */
        val DEFAULT_FLUSH_BOUND: Duration = 5.seconds

        /** The [TraceEvent.Drop.type] of the report that events were evicted unseen. */
        const val OVERFLOW_TYPE = "com.ditchoom.socket.quic.trace.OffLoopTraceSink.Overflow"
    }
}
