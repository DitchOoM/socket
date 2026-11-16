@file:OptIn(InternalQuicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.observe.EndedFlight
import com.ditchoom.socket.quic.core.observe.FlightEvidence
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/*
 * The core lanes of the Android probe: `core-v4` / `core-v6` run the run-to-completion QUIC client (`coreConnect`, with
 * `MigrationPolicy.Automatic`) beside the 4.x lanes, against the same server and with the same echo loop.
 *
 * `coreConnect`, `CoreConnection` and `FlightEvidence` are `internal` to :socket-quic-quiche; this test source set is
 * part of that module.
 */

/** How a core lane's connection ended, as far as keeping its flight recording goes. */
internal sealed interface CoreEnd {
    /** The walk ran out and this probe closed the connection itself: nothing went wrong, nothing is kept. */
    data object Clean : CoreEnd

    /** The connection ended for any other reason ([reason] is the typed close, rendered) or never completed its handshake. */
    data class Abnormal(
        val reason: String,
    ) : CoreEnd
}

/** The connection a core lane has open right now, for a snapshot of its flight recording when the app is trimmed. */
internal sealed interface LiveCore {
    data object None : LiveCore

    class On(
        val connection: CoreConnection,
    ) : LiveCore
}

/** A lane telling the evidence writer that its connection ended: [family] is the peer's (4 or 6), [stem] names the connection's files. */
internal class FlightRequest(
    val family: Int,
    val stem: String,
    val end: CoreEnd,
    val emit: (String) -> Unit,
)

/**
 * Connects to [host]:[port] on the core, registers the connection in [live] for as long as [block] runs, and closes
 * it afterwards. A connection that dies under [block] surfaces as the `QuicCloseException` with the typed reason, as
 * `withQuicConnection` does.
 */
internal suspend fun <R> coreConnection(
    host: String,
    port: Int,
    options: QuicOptions,
    live: AtomicReference<LiveCore>,
    block: suspend QuicScope.() -> R,
): R {
    val client =
        coreConnect(
            host = host,
            port = port,
            serverName = host,
            options = options,
            factory = BufferFactory.network(),
            api = loadQuicheApi(),
            timeout = CORE_HANDSHAKE_TIMEOUT,
            readPolicy = ReadPolicy.Bounded(CORE_READ_BOUND),
            writePolicy = WritePolicy.Bounded(CORE_READ_BOUND),
        )
    val connection = client.connection
    live.set(LiveCore.On(connection))
    try {
        return connection.runUntilClosed(options.idleTimeout) { block() }
    } finally {
        live.set(LiveCore.None)
        connection.close(QuicError.NoError)
    }
}

private val CORE_HANDSHAKE_TIMEOUT = 15.seconds
private val CORE_READ_BOUND = 30.seconds

/**
 * What the core's flight recorder says about the walk, kept on the device.
 *
 * Every connection the core ends hands its recording (the last records of every datagram, timer, operation and
 * network change it saw) to [FlightEvidence], which this keeps from the start of the walk. When a lane's connection
 * ends for any reason but this probe's own clean close, [ended] has it written to `<dir>/<connection stem>.flight.trace`
 * in the trace grammar. On the app being trimmed, [snapshot] writes the recording of every connection still open.
 * A byte budget bounds the directory; a recording dropped for it is a log line.
 */
internal class CoreEvidence(
    private val dir: File,
    private val budgetBytes: Long,
) {
    private val requests = Channel<FlightRequest>(Channel.UNLIMITED)
    private val spent = AtomicLong(0L)
    private val budgetAnnounced = AtomicInteger(0)

    // Only the writer coroutine touches this.
    private var held: List<EndedFlight> = emptyList()

    init {
        dir.mkdirs()
        FlightEvidence.keep()
    }

    /** Starts the writer, which is the only caller of [FlightEvidence.release] so that two lanes never race to take it. */
    fun start(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Default) {
            for (request in requests) handle(request)
        }

    fun ended(request: FlightRequest) {
        requests.trySend(request)
    }

    fun stop() {
        requests.close()
        FlightEvidence.release()
    }

    private fun handle(request: FlightRequest) {
        val taken = FlightEvidence.release()
        FlightEvidence.keep()
        val mine = (held + taken).partition { it.role == QuicRole.Client && it.peer.family == request.family }
        held = mine.second.takeLast(MAX_HELD)
        when (val end = request.end) {
            CoreEnd.Clean -> request.emit("FLIGHT-DROPPED stem=${request.stem} clean close, ${mine.first.size} recording(s) not kept")
            is CoreEnd.Abnormal -> {
                if (mine.first.isEmpty()) {
                    request.emit("FLIGHT-MISSING stem=${request.stem} reason=${end.reason} — no recording was handed over for this connection")
                }
                mine.first.forEachIndexed { n, flight ->
                    val suffix = if (n == 0) "" else "-$n"
                    write(
                        File(dir, "${request.stem}.flight$suffix.trace"),
                        flight.events.joinToString(separator = "\n", postfix = "\n") { it.toString() },
                        flight.events.size,
                        "ended reason=${end.reason}",
                        request.emit,
                    )
                }
            }
        }
    }

    /** Writes the recording of the connection a lane has [open], named by its [stem], under [label] (`trim-memory`, `low-memory`). */
    suspend fun snapshot(
        open: LiveCore,
        stem: String,
        emit: (String) -> Unit,
        label: String,
    ) {
        val live = open as? LiveCore.On ?: return
        val text = StringBuilder()
        var events = 0
        live.connection.dumpFlight(
            TraceSink { event ->
                text.append(event.toString()).append('\n')
                events++
            },
        )
        write(File(dir, "$stem.flight-$label.trace"), text.toString(), events, "snapshot app=$label", emit)
    }

    private fun write(
        file: File,
        text: String,
        events: Int,
        what: String,
        emit: (String) -> Unit,
    ) {
        val size = text.length.toLong()
        if (spent.addAndGet(size) > budgetBytes) {
            spent.addAndGet(-size)
            if (budgetAnnounced.incrementAndGet() == 1) emit("FLIGHT-BUDGET-SPENT bytes=${spent.get()} — flight recordings stop; later ones are dropped")
            emit("FLIGHT-DROPPED file=${file.name} events=$events $what — flight budget spent")
            return
        }
        runCatching { file.writeText(text) }
            .onSuccess { emit("FLIGHT-DUMP file=${file.name} events=$events bytes=$size $what") }
            .onFailure { emit("FLIGHT-UNWRITABLE file=${file.name} events=$events $what err=${it::class.simpleName}") }
    }

    private companion object {
        const val MAX_HELD = 16
    }
}

/** What a core connection reported of its path: how often it was moved, migrated, or refused the move. */
internal class CorePathTally {
    private val moved = AtomicInteger(0)
    private val migrated = AtomicInteger(0)
    private val refused = AtomicInteger(0)
    private val other = AtomicInteger(0)

    fun reset() {
        moved.set(0)
        migrated.set(0)
        refused.set(0)
        other.set(0)
    }

    fun note(event: TraceEvent) {
        if (event !is TraceEvent.PathState) return
        when (event.phase) {
            "Moved" -> moved.incrementAndGet()
            "Migrated" -> migrated.incrementAndGet()
            "Refused" -> refused.incrementAndGet()
            else -> other.incrementAndGet()
        }
    }

    val line: String get() = "moved=${moved.get()} migrated=${migrated.get()} refused=${refused.get()} otherPathEvents=${other.get()}"
}

/** True for the trace events that say how the core's connection sees its path: logged as they happen on a core lane. */
internal fun TraceEvent.isCorePathEvent(): Boolean =
    this is TraceEvent.PathState ||
        this is TraceEvent.State ||
        this is TraceEvent.Migration ||
        this is TraceEvent.Net ||
        this is TraceEvent.NetGap ||
        this is TraceEvent.Error ||
        this is TraceEvent.Drop
