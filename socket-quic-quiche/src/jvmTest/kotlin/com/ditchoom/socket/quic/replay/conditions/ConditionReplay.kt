package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.edge.CoreServer
import com.ditchoom.socket.quic.core.edge.EdgeEnd
import com.ditchoom.socket.quic.core.edge.feedNetwork
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.loadQuicheRandomPin
import com.ditchoom.socket.quic.read
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
) {
    private val clock = VirtualClock(Nanos(ORIGIN))
    private val factory = BufferFactory.Default
    private val echoes = ArrayList<Echo>()
    private val sleep = Sleeper()
    private var steps = 0L

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
        val link = ConditionLink(conditions, seed, clock)
        val server = replayServer(api, options, factory, link.serverIo, clock)
        link.server = InetSocketAddress(LOCALHOST, server.port).toPathKey()
        val client = replayClient(api, options, factory, link.clientIo, clock, server.port, seed)
        link.client = client.local
        val monitor = ScriptMonitor(conditions.start)
        client.parts.start()
        client.scope.feedNetwork(
            monitor,
            client.connection,
            client.parts,
            { monitor.routeSource() },
            LOCALHOST,
            coroutineContext.minusKey(Job),
        )
        val accepted = CompletableDeferred<Unit>()
        val serverApp = launch { echoBack(server, accepted) }
        var clientApp: AppStart = AppStart.Waiting
        val events = conditions.os.sortedBy { it.at }
        var nextEvent = 0
        val end = ORIGIN + conditions.runFor.inWholeNanoseconds
        var now = ORIGIN
        var finish: Finish = Finish.Lived
        while (true) {
            advanceTo(api, now)
            while (nextEvent < events.size && ORIGIN + events[nextEvent].at.inWholeNanoseconds <= now) {
                apply(events[nextEvent++], monitor, link)
            }
            sleep.wake(now)
            clientApp = clientApp.next(now, client.connection.state.value, accepted) { launch { echo(client) } }
            val loopsDue = settle(now, link, server.loop, client.loop, monitor)
            when (val ended = client.connection.end()) {
                is EdgeEnd.Ended -> {
                    finish = Finish.Ended(ended.end, script(now))
                    break
                }
                EdgeEnd.Open -> Unit
            }
            if (now >= end) break
            val eventDue = if (nextEvent < events.size) ORIGIN + events[nextEvent].at.inWholeNanoseconds else Long.MAX_VALUE
            val due = minOf(loopsDue, link.nextArrival(), eventDue, sleep.due(), clientApp.due(), end)
            now = maxOf(now + MIN_STEP, due)
        }
        clientApp.cancel()
        serverApp.cancel()
        client.connection.closeNow(QuicError.NoError)
        settle(now, link, server.loop, client.loop, monitor)
        stop(client.loop)
        stop(server.loop)
        client.scope.cancel()
        return Replayed(conditions, outcomeOf(conditions, echoes, finish), echoes.toList(), link.reopens)
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
                    if (read !is ScopedRead.Data<*>) return
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

    private suspend fun echoBack(
        server: CoreServer,
        accepted: CompletableDeferred<Unit>,
    ) {
        try {
            val connection = server.accept()
            accepted.complete(Unit)
            val stream = connection.acceptStream()
            while (stream.read(REPLAY_DEADLINE) { stream.writeFully(it, REPLAY_DEADLINE) } is ScopedRead.Data<*>) steps++
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
    }
}
