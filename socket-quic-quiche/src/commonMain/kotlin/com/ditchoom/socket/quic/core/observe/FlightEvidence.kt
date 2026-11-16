@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.observe

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.testkit.trace.TraceEvent
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One connection's flight recording as it stood when the connection ended. */
internal class EndedFlight(
    val role: QuicRole,
    val peer: PathKey,
    val events: List<TraceEvent>,
)

/** The recordings kept so far, newest first. */
internal sealed interface KeptFlights {
    data object None : KeptFlights

    class Then(
        val flight: EndedFlight,
        val before: KeptFlights,
    ) : KeptFlights
}

/** Whether connections that end hand their flight recording on, and what they handed. */
internal sealed interface Evidence {
    /** Nothing is kept: a connection's end costs this one read. */
    data object Off : Evidence

    class Keeping(
        val kept: KeptFlights,
    ) : Evidence
}

/**
 * Where the flight recordings of ended connections are kept for a process that asks for them, so a failed test can
 * write what every connection it touched saw. Off unless [keep] was called.
 */
@Published
internal object FlightEvidence {
    private val state = AtomicReference<Evidence>(Evidence.Off)

    fun current(): Evidence = state.load()

    /** Starts keeping the recording of every connection that ends from now on, forgetting what was kept. */
    fun keep() = state.store(Evidence.Keeping(KeptFlights.None))

    /** Stops keeping and returns what was kept, oldest first. */
    fun release(): List<EndedFlight> {
        val last = state.exchange(Evidence.Off)
        val kept = ArrayList<EndedFlight>()
        var at =
            when (last) {
                is Evidence.Off -> KeptFlights.None
                is Evidence.Keeping -> last.kept
            }
        while (true) {
            when (at) {
                is KeptFlights.None -> return kept.asReversed()
                is KeptFlights.Then -> {
                    kept += at.flight
                    at = at.before
                }
            }
        }
    }

    /** Any thread: keeps [flight] while keeping is on. */
    fun add(flight: EndedFlight) {
        while (true) {
            when (val seen = state.load()) {
                is Evidence.Off -> return
                is Evidence.Keeping ->
                    if (state.compareAndSet(seen, Evidence.Keeping(KeptFlights.Then(flight, seen.kept)))) return
            }
        }
    }
}
