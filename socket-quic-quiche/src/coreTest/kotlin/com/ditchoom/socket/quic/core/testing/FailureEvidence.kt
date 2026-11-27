package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.core.observe.EndedFlight
import com.ditchoom.socket.quic.core.observe.FlightEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** What a failed test's evidence came to. */
internal sealed interface EvidenceWritten {
    /** The recordings of [count] connections are in [directory], one trace file each, named for the test; [ends] says how each ended. */
    class Files(
        val directory: String,
        val test: String,
        val count: Int,
        val ends: String,
    ) : EvidenceWritten

    /** The test touched no connection that ended. */
    data object NoConnections : EvidenceWritten

    /** [directory] could not be written. */
    class Unwritable(
        val directory: String,
    ) : EvidenceWritten
}

/**
 * The flight recording of every connection a core test touched, kept while the test runs and written only when it
 * fails. The recordings are the ones every connection already makes; this adds one copy per connection at its end.
 */
internal object FailureEvidence {
    /** Starts keeping the recording of each connection that ends. */
    fun begin() = FlightEvidence.keep()

    /** The test passed: what was kept is dropped and nothing is written. */
    fun passed() {
        FlightEvidence.release()
    }

    /** The test [test] (`Class.method`) failed: writes what was kept under [flightDirectory] and says where. */
    fun failed(test: String): EvidenceWritten {
        val ended = FlightEvidence.release()
        if (ended.isEmpty()) return EvidenceWritten.NoConnections
        val directory = flightDirectory() + "/" + test.substringBeforeLast('.')
        val method = test.substringAfterLast('.')
        if (!makeDirectories(directory)) return EvidenceWritten.Unwritable(directory)
        val written =
            ended.withIndex().count { (n, flight) ->
                writeTextFile("$directory/$method.${nameOf(flight, n)}.trace", linesOf(flight))
            }
        val ends = ended.withIndex().joinToString { (n, flight) -> "${nameOf(flight, n)} ended ${flight.end}" }
        return if (written == ended.size) EvidenceWritten.Files(directory, method, written, ends) else EvidenceWritten.Unwritable(directory)
    }

    /** The message a failed test carries: names where its recordings went. */
    fun message(
        written: EvidenceWritten,
        original: String?,
    ): String =
        original.orEmpty() +
            when (written) {
                is EvidenceWritten.Files ->
                    "\nflight recordings (${written.count} connections: ${written.ends}): ${written.directory}/${written.test}.*.trace"
                is EvidenceWritten.NoConnections -> "\nflight recordings: no connection ended during the test"
                is EvidenceWritten.Unwritable -> "\nflight recordings: could not write under ${written.directory}"
            }

    private fun nameOf(
        flight: EndedFlight,
        n: Int,
    ) = when (flight.role) {
        QuicRole.Client -> "client-$n"
        QuicRole.Server -> "server-$n"
    }

    private fun linesOf(flight: EndedFlight): String {
        val text = StringBuilder()
        for (event in flight.events) text.append(event.toString()).append('\n')
        return text.toString()
    }
}

/**
 * [runBlocking] for the core test [test] (`Class.method`): when it fails, its connections' recordings are written and
 * the failure names them.
 */
internal fun <T> coreTest(
    test: String,
    body: suspend CoroutineScope.() -> T,
): T {
    FailureEvidence.begin()
    try {
        return runBlocking(block = body).also { FailureEvidence.passed() }
    } catch (failure: Throwable) {
        val written = FailureEvidence.failed(test)
        throw AssertionError(FailureEvidence.message(written, failure.message ?: failure.toString()), failure)
    }
}
