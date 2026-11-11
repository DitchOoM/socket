@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.socket.udp.nw.nw_udp_now_ns
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.getloadavg
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong

/**
 * When each step of one Network.framework UDP connect happened, so that a failed or slow connect can say
 * where its time went.
 *
 * Why it exists: on two CI runs NW took 6.4 s and 7.4 s to deliver its first `waiting` report for an
 * unsatisfiable required-interface connect, where it takes 1–5 ms locally even with the CPUs
 * oversubscribed. Nothing recorded whether that time went into creating the connection, into NW's
 * path evaluation before the first report, or into the callback reaching Kotlin. This records each of
 * those separately: every state change carries both the instant NW entered the C block and the instant
 * the Kotlin handler ran, on the same clock.
 *
 * Recording costs one small allocation and one atomic increment per event, a handful per connect, and
 * nothing is formatted unless [render] is called — on a failed connect, or by a test that saw a slow
 * one. Writes come from NW's concurrent queue and the connecting coroutine; each event takes its own
 * slot, and events past [CAPACITY] are counted but not kept.
 */
internal class NwConnectTrace(
    private val now: () -> Long = { nw_udp_now_ns().toLong() },
) {
    private val origin: Long = now()

    /** This process's connect count, this one included: the first NW connection of a process is the slow suspect. */
    val ordinal: Int = connectCount.incrementAndGet()
    private val sinceFirstConnectNs: Long = origin - firstConnectAt(origin)
    private val events = arrayOfNulls<Event>(CAPACITY)
    private val recorded = AtomicInt(0)

    private class Event(
        val what: String,
        val kotlinNs: Long,
        val nwEntryNs: Long = NOT_FROM_NW,
        val state: Int = 0,
        val errorDomain: Int = 0,
        val errorCode: Int = 0,
        val pathStatus: Int = 0,
        val unsatisfiedReason: Int = 0,
    )

    /** A step of the connect itself: creating, starting, the caller cancelling. */
    fun mark(what: String) = record(Event(what, now()))

    /** One NW state change, [nwEntryNs] being when NW entered the C block that reported it. */
    fun state(
        state: Int,
        errorDomain: Int,
        errorCode: Int,
        pathStatus: Int,
        unsatisfiedReason: Int,
        nwEntryNs: Long,
    ) = record(Event("state", now(), nwEntryNs, state, errorDomain, errorCode, pathStatus, unsatisfiedReason))

    private fun record(event: Event) {
        val slot = recorded.getAndIncrement()
        if (slot < CAPACITY) events[slot] = event
    }

    /** The timeline, one line per event, in milliseconds from this trace's creation. */
    fun render(): String =
        buildString {
            append("NW UDP connect #").append(ordinal).append(" in this process")
            if (ordinal > 1) append(", ").append(ms(sinceFirstConnectNs)).append(" ms after the first")
            append("; load average ").append(loadAverage())
            append("; rendered at +").append(ms(now() - origin)).append(" ms")
            val count = recorded.value
            for (i in 0 until minOf(count, CAPACITY)) {
                val event = events[i] ?: continue
                append("\n  +").append(ms(event.kotlinNs - origin)).append(" ms ")
                if (event.nwEntryNs == NOT_FROM_NW) {
                    append(event.what)
                    continue
                }
                append("state=").append(stateName(event.state))
                append(" path=").append(pathName(event.pathStatus))
                if (event.pathStatus == NW_PATH_UNSATISFIED) append(" reason=").append(event.unsatisfiedReason)
                if (event.errorDomain != 0) append(" error=").append(event.errorDomain).append('/').append(event.errorCode)
                append(" (NW reported at +").append(ms(event.nwEntryNs - origin))
                append(" ms, reached Kotlin ").append(ms(event.kotlinNs - event.nwEntryNs)).append(" ms later)")
            }
            if (count > CAPACITY) append("\n  … ").append(count - CAPACITY).append(" later events not kept")
        }

    /**
     * One line saying how long NW took to deliver its first state report, from the start of this trace,
     * and when Kotlin saw it. Printed by tests on every run, fast or slow, so a CI log yields the
     * latency distribution rather than only its outliers.
     */
    fun firstReportSummary(): String {
        val first =
            (0 until minOf(recorded.value, CAPACITY))
                .firstNotNullOfOrNull { i -> events[i]?.takeIf { it.nwEntryNs != NOT_FROM_NW } }
        val head = "connect #$ordinal"
        val tail = "load average ${loadAverage()}"
        if (first == null) return "$head: NW never reported; $tail"
        return "$head: NW first reported at +${ms(first.nwEntryNs - origin)} ms, " +
            "Kotlin saw it at +${ms(first.kotlinNs - origin)} ms; $tail"
    }

    private fun loadAverage(): String =
        memScoped {
            val loads = allocArray<DoubleVar>(3)
            if (getloadavg(loads, 3) != 3) return@memScoped "unavailable"
            "${round2(loads[0])} ${round2(loads[1])} ${round2(loads[2])}"
        }

    companion object {
        const val CAPACITY = 32
        private const val NOT_FROM_NW = Long.MIN_VALUE
        private val connectCount = AtomicInt(0)
        private val firstConnect = AtomicLong(0L)

        private fun firstConnectAt(candidate: Long): Long {
            firstConnect.compareAndSet(0L, candidate)
            return firstConnect.value
        }

        private fun ms(ns: Long): String {
            val tenthsOfMicros = ns / 100 // 0.1 µs resolution is plenty
            val whole = tenthsOfMicros / 10_000
            val frac = (if (tenthsOfMicros < 0) -tenthsOfMicros else tenthsOfMicros) % 10_000
            return "$whole.${frac.toString().padStart(4, '0').take(3)}"
        }

        private fun round2(d: Double): String {
            val hundredths = (d * 100).toLong()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }

        private fun stateName(state: Int) =
            when (state) {
                0 -> "invalid"
                1 -> "waiting"
                2 -> "preparing"
                3 -> "ready"
                4 -> "failed"
                5 -> "cancelled"
                else -> "unknown($state)"
            }

        private fun pathName(status: Int) =
            when (status) {
                0 -> "none"
                NW_PATH_SATISFIED -> "satisfied"
                NW_PATH_UNSATISFIED -> "unsatisfied"
                NW_PATH_SATISFIABLE -> "satisfiable"
                else -> "unknown($status)"
            }
    }
}

/**
 * A failed NW connect's [NwConnectTrace], attached as a suppressed exception to the
 * [UdpConnectException] it explains: it prints with the stack trace and changes nothing a caller
 * branches on.
 */
internal class NwConnectTimeline(
    timeline: String,
) : Throwable(timeline)
