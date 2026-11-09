package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_now_ns
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.getloadavg
import kotlin.concurrent.AtomicInt

/**
 * When each step of a Network.framework TCP connect or listener happened, so a failure can say where
 * its time went.
 *
 * Why: `ExceptionIntegrationTests.tlsToNonTlsServer_producesSSLSocketException` failed on the CI iOS
 * simulator lane with the TLS connect timing out after its 5 s bound, in a test that took 12.2 s in
 * all. Nothing recorded whether the listener took the missing seven seconds to become ready, whether
 * the server ever saw the connection, or which state NW left the client in. Every NW state change is
 * recorded twice on one monotonic clock: when NW entered the C callback, and when the Kotlin handler
 * ran. A gap between the two is this process being starved, not NW being slow.
 *
 * Recording is one small allocation and one atomic increment per event, a handful per connection; it
 * is formatted only by [render], on a failure. Events past [CAPACITY] are counted, not kept.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwStateTrace(
    private val what: String,
    private val now: () -> Long = { nw_helper_now_ns().toLong() },
) {
    private val origin = now()
    private val events = arrayOfNulls<Event>(CAPACITY)
    private val recorded = AtomicInt(0)

    private class Event(
        val label: String,
        val kotlinNs: Long,
        val nwEntryNs: Long,
        val stateName: String?,
        val errorDomain: Int,
        val errorCode: Int,
        val errorDesc: String?,
    )

    /** A step of ours: create, start, probe result, cancel. */
    fun mark(label: String) = record(Event(label, now(), NOT_FROM_NW, null, 0, 0, null))

    /** A connection state change, [nwEntryNs] being when NW entered the C block that reported it. */
    fun connectionState(
        label: String,
        state: Int,
        errorDomain: Int,
        errorCode: Int,
        errorDesc: String?,
        nwEntryNs: Long,
    ) = record(Event(label, now(), nwEntryNs, connectionStateName(state), errorDomain, errorCode, errorDesc))

    /** A listener state change, [nwEntryNs] being when NW entered the C block that reported it. */
    fun listenerState(
        state: Int,
        errorDomain: Int,
        errorCode: Int,
        errorDesc: String?,
        nwEntryNs: Long,
    ) = record(Event("listener", now(), nwEntryNs, listenerStateName(state), errorDomain, errorCode, errorDesc))

    private fun record(event: Event) {
        val slot = recorded.getAndIncrement()
        if (slot < CAPACITY) events[slot] = event
    }

    fun render(): String =
        buildString {
            append(what)
                .append("; +0 is uptime ")
                .append(ms(origin))
                .append(" ms; load average ")
                .append(loadAverage())
            append("; rendered at +").append(ms(now() - origin)).append(" ms")
            val count = recorded.value
            for (i in 0 until minOf(count, CAPACITY)) {
                val event = events[i] ?: continue
                append("\n  +").append(ms(event.kotlinNs - origin)).append(" ms ").append(event.label)
                if (event.nwEntryNs == NOT_FROM_NW) continue
                append(" state=").append(event.stateName)
                if (event.errorDomain != 0) {
                    append(" error=").append(event.errorDomain).append('/').append(event.errorCode)
                    event.errorDesc?.let { append(" (").append(it).append(')') }
                }
                append(" (NW reported at +").append(ms(event.nwEntryNs - origin))
                append(" ms, reached Kotlin ").append(ms(event.kotlinNs - event.nwEntryNs)).append(" ms later)")
            }
            if (count > CAPACITY) append("\n  … ").append(count - CAPACITY).append(" later events not kept")
        }

    private fun loadAverage(): String =
        memScoped {
            val loads = allocArray<DoubleVar>(3)
            if (getloadavg(loads, 3) != 3) return@memScoped "unavailable"
            "${round2(loads[0])} ${round2(loads[1])} ${round2(loads[2])}"
        }

    companion object {
        const val CAPACITY = 48
        private const val NOT_FROM_NW = Long.MIN_VALUE

        private fun ms(ns: Long): String {
            val tenthsOfMicros = ns / 100
            val whole = tenthsOfMicros / 10_000
            val frac = (if (tenthsOfMicros < 0) -tenthsOfMicros else tenthsOfMicros) % 10_000
            return "$whole.${frac.toString().padStart(4, '0').take(3)}"
        }

        private fun round2(d: Double): String {
            val hundredths = (d * 100).toLong()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }

        private fun connectionStateName(state: Int) =
            when (state) {
                0 -> "setup"
                1 -> "waiting"
                2 -> "preparing"
                3 -> "ready"
                4 -> "failed"
                5 -> "cancelled"
                else -> "unknown($state)"
            }

        private fun listenerStateName(state: Int) =
            when (state) {
                0 -> "setup"
                1 -> "waiting"
                2 -> "ready"
                3 -> "failed"
                4 -> "cancelled"
                else -> "unknown($state)"
            }
    }
}

/**
 * A failed NW connect's [NwStateTrace], attached as a suppressed exception to the exception it
 * explains: it prints with the stack trace and changes nothing a caller branches on.
 */
internal class NwStateTimeline(
    timeline: String,
) : Throwable(timeline)
