package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.edge.CoreServer
import com.ditchoom.socket.quic.core.edge.EdgeEnd
import com.ditchoom.socket.quic.core.edge.feedNetwork
import com.ditchoom.socket.quic.core.edge.linkClassOf
import com.ditchoom.socket.quic.core.edge.onMoveOf
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.loadQuicheRandomPin
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Replays [conditions] against a real core client and a real core server on one thread and one virtual clock, quiche's
 * own clock included: the link ([ConditionLink]) loses and delays as each window says, the OS events reach the client's
 * network feed through a scripted monitor ([ScriptMonitor]), and the client echoes every
 * [Conditions.echoEvery], each echo held until the last was answered, as the walk probe does. The run ends when the
 * client's connection ends or the script does, and its [Outcome] is what a test asserts. Quiche's randomness is pinned
 * to [seed]: the same script gives the same outcome.
 */
internal class ConditionReplay(
    private val conditions: Conditions,
    private val seed: Long = 1L,
    /** How many of the first datagrams the server sends to the client's moved address are lost. */
    private val loseToMoved: Int = 0,
    /** What the client's application does once the connection is up. */
    private val workload: Workload = Workload.Echo,
    /** When a NAT in front of the client rebinds it to a new port ([ConditionLink.rebind]), on the script's clock. */
    private val rebinds: List<Duration> = emptyList(),
) {
    private val clock = VirtualClock(Nanos(ORIGIN))
    private val factory = BufferFactory.Default
    private val echoes = ArrayList<Echo>()
    private val sleep = Sleeper()
    private val marks = LinkedHashSet<PathMarkAt>()
    private var steps = 0L

    /** When the client saw its handshake complete, once. */
    private val established = ArrayList<Long>(1)

    /** When each download of [Workload.Download] was read to its end. */
    private val downloaded = ArrayList<Long>(1)

    /** What the client's silence watch said, each change when the replay first saw it. */
    private val news = ArrayList<PathMarkAt>()
    private var told: PathNews = PathNews.Answering

    fun run(): Replayed =
        runBlocking {
            val api = loadQuicheApi()
            val pin = loadQuicheRandomPin()
            api.setThreadVirtualTimeNanos(ORIGIN)
            pin.setThreadRandomState(seed)
            try {
                drive(api)
            } finally {
                pin.clearThreadRandomState()
                api.clearThreadVirtualTime()
            }
        }

    private suspend fun CoroutineScope.drive(api: com.ditchoom.socket.quic.QuicheApi): Replayed {
        val options = replayOptions(conditions)
        val link = ConditionLink(conditions, seed, clock, loseToMoved)
        val server = replayServer(api, options, factory, link.serverIo, clock)
        link.server = InetSocketAddress(LOCALHOST, server.port).toPathKey()
        val monitor = ScriptMonitor(conditions.start)
        val client =
            replayClient(api, options, factory, link.clientIo, clock, server.port, seed, linkClassOf(monitor.state.value.networkId))
        link.client = client.local
        client.parts.start()
        client.scope.feedNetwork(
            monitor,
            client.connection,
            client.parts,
            { monitor.routeSource() },
            LOCALHOST,
            onMoveOf(options.migration),
            coroutineContext.minusKey(Job),
            // The link picks the moved socket's address itself; nothing here may leave the replay's thread.
            { link.client },
        )
        val accepted = CompletableDeferred<Unit>()
        val serverApp =
            when (val w = workload) {
                Workload.Echo -> launch { echoBack(server, accepted) }
                is Workload.Download -> launch { serveDownload(server, accepted, w.bytes) }
                is Workload.Writes -> launch { drop(server, accepted) }
            }
        var clientApp: AppStart = AppStart.Waiting
        val events = conditions.os.sortedBy { it.at }
        var nextEvent = 0
        var nextRebind = 0
        val end = ORIGIN + conditions.runFor.inWholeNanoseconds
        var now = ORIGIN
        var finish: Finish = Finish.Lived
        var nextDump = ORIGIN
        while (true) {
            advanceTo(api, now)
            while (nextEvent < events.size && ORIGIN + events[nextEvent].at.inWholeNanoseconds <= now) {
                apply(events[nextEvent++], monitor, link)
            }
            while (nextRebind < rebinds.size && ORIGIN + rebinds[nextRebind].inWholeNanoseconds <= now) {
                link.rebind()
                nextRebind++
            }
            sleep.wake(now)
            clientApp =
                clientApp.next(now, client.connection.state.value, accepted) {
                    when (val w = workload) {
                        Workload.Echo -> launch { echo(client) }
                        is Workload.Download -> launch { download(client, w.bytes) }
                        is Workload.Writes -> launch { write(client, w.every) }
                    }
                }
            if (established.isEmpty() && client.connection.state.value is QuicConnectionState.Established) established += now
            val loopsDue = settle(now, link, server.loop, client.loop, monitor)
            val heard = client.parts.news.read()
            if (heard != told) {
                told = heard
                news += PathMarkAt(heard::class.simpleName.orEmpty(), (now - ORIGIN).nanoseconds)
            }
            when (val ended = client.connection.end()) {
                is EdgeEnd.Ended -> {
                    finish = Finish.Ended(ended.end, script(now))
                    break
                }
                EdgeEnd.Open -> Unit
            }
            if (link.moves > 0 && now >= nextDump) {
                launch { dumpMarks(client) }
                nextDump = now + DUMP_EVERY.inWholeNanoseconds
            }
            if (now >= end) break
            val eventDue = if (nextEvent < events.size) ORIGIN + events[nextEvent].at.inWholeNanoseconds else Long.MAX_VALUE
            val dumpDue = if (link.moves > 0) nextDump else Long.MAX_VALUE
            val rebindDue = if (nextRebind < rebinds.size) ORIGIN + rebinds[nextRebind].inWholeNanoseconds else Long.MAX_VALUE
            val due = minOf(loopsDue, link.nextArrival(), eventDue, sleep.due(), clientApp.due(), dumpDue, rebindDue, end)
            now = maxOf(now + MIN_STEP, due)
        }
        clientApp.cancel()
        serverApp.cancel()
        val dump = launch { dumpMarks(client) }
        settle(now, link, server.loop, client.loop, monitor)
        dump.cancel()
        client.connection.closeNow(QuicError.NoError)
        settle(now, link, server.loop, client.loop, monitor)
        stop(client.loop)
        stop(server.loop)
        client.scope.cancel()
        return Replayed(
            conditions,
            outcomeOf(conditions, echoes, finish),
            echoes.toList(),
            link.reopens,
            link.moves,
            link.clientSends.map { it.nanoseconds },
            link.clientSizes.toList(),
            link.serverSends.map { it.nanoseconds },
            link.serverSizes.toList(),
            established.map { (it - ORIGIN).nanoseconds },
            marks.sortedBy { it.at },
            downloaded.map { (it - ORIGIN).nanoseconds },
            news.toList(),
            link.clientArrivals.map { it.nanoseconds },
            link.duplicated,
        )
    }

    /**
     * Reads the client's path marks out of its flight recording into [marks]: read every [DUMP_EVERY] once the socket
     * moved, as the recording's ring keeps only its latest records.
     */
    private suspend fun dumpMarks(client: ReplayClient) {
        client.connection.dumpFlight(TraceSink { event -> if (event is TraceEvent.PathState) marks += PathMarkAt(event.phase, event.at) })
    }

    private fun advanceTo(
        api: com.ditchoom.socket.quic.QuicheApi,
        now: Long,
    ) {
        clock.at = Nanos(now)
        api.setThreadVirtualTimeNanos(now)
    }

    private fun apply(
        event: OsEvent,
        monitor: ScriptMonitor,
        link: ConditionLink,
    ) {
        when (event) {
            is OsEvent.Offline -> link.os = OsNow.Offline
            is OsEvent.Up -> link.os = OsNow.On(event.up.network)
            is OsEvent.Route -> Unit
        }
        monitor.said(event)
    }

    /** Passes both loops and lets the coroutines run until nothing moves: no datagram, no work, no echo, no OS news. */
    private suspend fun settle(
        now: Long,
        link: ConditionLink,
        server: Loop,
        client: Loop,
        monitor: ScriptMonitor,
    ): Long {
        var due = Long.MAX_VALUE
        var quiet = 0
        var rounds = 0
        while (quiet < QUIET_ROUNDS && rounds++ < MAX_ROUNDS) {
            val before = link.moved + steps + monitor.readings + server.counts.work + client.counts.work
            link.deliverDue(now)
            due = minOf(server.pass(Nanos(now)).fold({ Long.MAX_VALUE }, { it }), client.pass(Nanos(now)).fold({ Long.MAX_VALUE }, { it }))
            repeat(YIELDS) { yield() }
            val after = link.moved + steps + monitor.readings + server.counts.work + client.counts.work
            quiet = if (after == before) quiet + 1 else 0
        }
        return due
    }

    /** The probe's echo: [Conditions.echoBytes] out, the same back, then the next one [Conditions.echoEvery] after this one left. */
    private suspend fun echo(client: ReplayClient) {
        try {
            val stream = client.connection.openStream()
            var next = clock.at.value
            while (true) {
                sleep.until(next)
                val sent = clock.at.value
                val payload =
                    factory.allocate(conditions.echoBytes).apply {
                        repeat(conditions.echoBytes) { writeByte((echoes.size + it).toByte()) }
                        resetForRead()
                    }
                stream.writeFully(payload, REPLAY_DEADLINE)
                var got = 0
                while (got < conditions.echoBytes) {
                    val read = stream.read(REPLAY_DEADLINE) { got += it.readByteArray(it.remaining()).size }
                    if (read !is ScopedReadResult.Data<*>) return
                }
                echoes += Echo(script(sent), script(clock.at.value))
                steps++
                next = sent + conditions.echoEvery.inWholeNanoseconds
            }
        } catch (ended: Exception) {
            // The connection ended under the echo: the outcome says how.
            steps++
        }
    }

    /** One request of [PROBE_ECHO_BYTES] out, then [bytes] read back on the same stream; when the last arrived is kept. */
    private suspend fun download(
        client: ReplayClient,
        bytes: Int,
    ) {
        try {
            val stream = client.connection.openStream()
            val request = factory.allocate(PROBE_ECHO_BYTES).apply { repeat(PROBE_ECHO_BYTES) { writeByte(it.toByte()) } }
            request.resetForRead()
            stream.writeFully(request, REPLAY_DEADLINE)
            var got = 0
            while (got < bytes) {
                val read = stream.read(REPLAY_DEADLINE) { got += it.readByteArray(it.remaining()).size }
                if (read !is ScopedReadResult.Data<*>) return
                steps++
            }
            downloaded += clock.at.value
            steps++
        } catch (ended: Exception) {
            steps++
        }
    }

    /** [PROBE_ECHO_BYTES] every [every] on one stream, never read back. */
    private suspend fun write(
        client: ReplayClient,
        every: Duration,
    ) {
        try {
            val stream = client.connection.openStream()
            while (true) {
                val chunk = factory.allocate(PROBE_ECHO_BYTES).apply { repeat(PROBE_ECHO_BYTES) { writeByte(it.toByte()) } }
                chunk.resetForRead()
                stream.writeFully(chunk, REPLAY_DEADLINE)
                steps++
                sleep.until(clock.at.value + every.inWholeNanoseconds)
            }
        } catch (ended: Exception) {
            steps++
        }
    }

    /** Reads whatever the client writes, and drops it. */
    private suspend fun drop(
        server: CoreServer,
        accepted: CompletableDeferred<Unit>,
    ) {
        try {
            val connection = server.accept()
            accepted.complete(Unit)
            val stream = connection.acceptStream()
            while (stream.read(REPLAY_DEADLINE) { it.readByteArray(it.remaining()) } is ScopedReadResult.Data<*>) steps++
        } catch (ended: Exception) {
            steps++
        }
    }

    /** Reads the client's request, then writes [bytes] back. */
    private suspend fun serveDownload(
        server: CoreServer,
        accepted: CompletableDeferred<Unit>,
        bytes: Int,
    ) {
        try {
            val connection = server.accept()
            accepted.complete(Unit)
            val stream = connection.acceptStream()
            var got = 0
            while (got < PROBE_ECHO_BYTES) {
                if (stream.read(REPLAY_DEADLINE) { got += it.readByteArray(it.remaining()).size } !is ScopedReadResult.Data<*>) return
            }
            val response = factory.allocate(bytes).apply { repeat(bytes) { writeByte(it.toByte()) } }
            response.resetForRead()
            stream.writeFully(response, REPLAY_DEADLINE)
            steps++
        } catch (ended: Exception) {
            steps++
        }
    }

    private suspend fun echoBack(
        server: CoreServer,
        accepted: CompletableDeferred<Unit>,
    ) {
        try {
            val connection = server.accept()
            accepted.complete(Unit)
            val stream = connection.acceptStream()
            while (stream.read(REPLAY_DEADLINE) { stream.writeFully(it, REPLAY_DEADLINE) } is ScopedReadResult.Data<*>) steps++
        } catch (ended: Exception) {
            steps++
        }
    }

    private fun stop(loop: Loop) {
        loop.close(Runnable { })
        loop.run()
    }

    private fun script(nanos: Long): Duration = (nanos - ORIGIN).nanoseconds

    /** The client's echo: waiting for the handshake, then for the server to have accepted, then running. */
    private sealed interface AppStart {
        fun due(): Long = Long.MAX_VALUE

        fun cancel() = Unit

        data object Waiting : AppStart

        class Established(
            val at: Long,
        ) : AppStart {
            override fun due(): Long = at + WARM_UP.inWholeNanoseconds
        }

        class Running(
            val job: Job,
        ) : AppStart {
            override fun cancel() = job.cancel()
        }

        /**
         * The echo starts [WARM_UP] after the handshake, once the server's application holds the connection: the
         * server offers it from a thread of its own, so the replay waits for that on the wall clock and not on its own.
         */
        suspend fun next(
            now: Long,
            state: QuicConnectionState,
            accepted: CompletableDeferred<Unit>,
            start: () -> Job,
        ): AppStart =
            when (this) {
                Waiting -> if (state is QuicConnectionState.Established) Established(now) else this
                is Established ->
                    if (now >= due()) {
                        withTimeout(ACCEPT_WITHIN) { accepted.await() }
                        Running(start())
                    } else {
                        this
                    }
                is Running -> this
            }
    }

    private companion object {
        const val ORIGIN = 1_000_000_000L
        const val MIN_STEP = 1_000L
        const val QUIET_ROUNDS = 2
        const val MAX_ROUNDS = 1_000
        const val YIELDS = 4
        val WARM_UP = 1.seconds
        val ACCEPT_WITHIN = 10.seconds
        val DUMP_EVERY = 100.milliseconds
    }
}
