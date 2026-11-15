package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the many-connection workload needs from the platform it runs on. */
internal interface ManyConnectionHost {
    /** The `stack=` value of the RESULT line. */
    val stack: String

    /** User + system CPU time of the whole process, in microseconds. */
    fun cpuMicros(): Long

    /** The process's peak resident set as the platform reports it (see benchmarks/native-compare/run.py). */
    fun maxRssRaw(): Long

    /** Datagrams the kernel dropped per UDP socket, by local port (Linux /proc/net/udp and udp6); empty elsewhere. */
    fun socketDrops(): String = ""

    /** Counters of the platform's own (measurement only); empty where there are none. */
    fun platformCounters(): String = ""

    /** The host's UDP error counters as one line (Linux: the `Udp:` rows of /proc/net/snmp), or why there are none. */
    fun udpCounters(): String
}

/**
 * Comparison workload, not a regression test (benchmarks/native-compare): [conns] connections spread over [servers]
 * in-process servers, each echoing 64 B on its own long-lived stream from its own coroutine on [Dispatchers.Default].
 * The same code runs on Kotlin/Native (`NativeCompareBench`) and on the JVM (`ManyConnectionEchoMain`).
 *
 * Every connection first echoes once (which also tells the server which connection it is), and the warm-up starts
 * once all have (`connect_s`). Lines printed:
 * - `RESULT`: aggregate operations per second and the median over connections of each one's p50 and p99;
 * - `PROGRESS`: when each connection's first measured echo completed, and how long the rest have waited;
 * - `UDP`: the host's UDP error counters before connecting and after the window;
 * - `STUCK`: for every connection with no echo completed in the window (the first [MAX_DUMPED]), when it connected
 *   and its client and server driver snapshots; `WORKING`: the same for one connection that did complete echoes.
 */
internal suspend fun manyConnectionEcho(
    host: ManyConnectionHost,
    conns: Int,
    servers: Int,
    warmup: Duration,
    window: Duration,
    tls: QuicTlsConfig,
    options: QuicOptions,
) {
    println("UDP before ${host.udpCounters()}")
    val serverScopes = arrayOfNulls<QuicScope>(conns)
    withEchoServers(servers, emptyList(), tls, options, serverScopes) { ports ->
        coroutineScope {
            val samples = Array(conns) { LongArray(1 shl 12) }
            val counts = IntArray(conns)
            val firstDone = LongArray(conns) { NOT_YET }
            val opStarted = LongArray(conns) { NOT_YET }
            val connectedAfterNanos = LongArray(conns) { NOT_YET }
            val clientScopes = arrayOfNulls<QuicScope>(conns)
            val ready = List(conns) { CompletableDeferred<Unit>() }
            val go = CompletableDeferred<TimeSource.Monotonic.ValueTimeMark>()
            // Connections stay open until the report has read their drivers.
            val reported = CompletableDeferred<Unit>()
            val connectStart = TimeSource.Monotonic.markNow()
            val clients =
                List(conns) { i ->
                    launch(Dispatchers.Default) {
                        withQuicConnection("127.0.0.1", ports[i % ports.size], options, timeout = 30.minutes) {
                            connectedAfterNanos[i] = connectStart.elapsedNow().inWholeNanoseconds
                            clientScopes[i] = this
                            val stream = openStream()
                            val request = bufferFactory.allocate(REQUEST_SIZE)
                            request.writeByte(ECHO)
                            i.toString(16).padStart(INDEX_DIGITS, '0').forEach { request.writeByte(it.code.toByte()) }
                            repeat(REQUEST_SIZE - 1 - INDEX_DIGITS) { request.writeByte('r'.code.toByte()) }
                            request.resetForRead()
                            stream.writeFully(request, 30.seconds)
                            stream.readEcho()
                            ready[i].complete(Unit)
                            val started = go.await()
                            val measureFrom = started + warmup
                            val end = measureFrom + window
                            while (!end.hasPassedNow()) {
                                val t = TimeSource.Monotonic.markNow()
                                opStarted[i] = started.elapsedNow().inWholeNanoseconds
                                request.position(0)
                                stream.writeFully(request, 30.seconds)
                                stream.readEcho()
                                if (firstDone[i] == NOT_YET) firstDone[i] = started.elapsedNow().inWholeNanoseconds
                                if (t > measureFrom && !end.hasPassedNow()) {
                                    if (counts[i] == samples[i].size) samples[i] = samples[i].copyOf(counts[i] * 2)
                                    samples[i][counts[i]++] = t.elapsedNow().inWholeNanoseconds
                                }
                            }
                            reported.await()
                            stream.shutdownSend()
                            stream.close()
                            request.freeIfNeeded()
                        }
                    }
                }
            ready.awaitAll()
            val connectSeconds = connectStart.elapsedNow().inWholeNanoseconds / 1e9
            println("CONNECTED conns=$conns connect_s=$connectSeconds")
            val started = TimeSource.Monotonic.markNow()
            go.complete(started)
            delay(warmup)
            println("MEASURING")
            println("INTAKE start ${IntakeExperiment.counters()} ${host.platformCounters()}")
            val cpu0 = host.cpuMicros()
            delay(window)
            val cpuUs = host.cpuMicros() - cpu0
            println("INTAKE end ${IntakeExperiment.counters()} ${host.platformCounters()}")
            println("UDP after ${host.udpCounters()}")
            println("UDPSOCK server_ports=$ports ${host.socketDrops()}")
            reportProgress(firstDone, opStarted, started.elapsedNow().inWholeNanoseconds)
            reportDrivers(clientScopes, serverScopes, counts, connectedAfterNanos)
            reportResult(host, conns, samples, counts, window, cpuUs, connectSeconds)
            reported.complete(Unit)
            // A connection still inside an echo that never completes is cancelled, not waited for.
            withTimeoutOrNull(5.seconds) { clients.joinAll() } ?: clients.forEach { it.cancel() }
        }
    }
}

/** Runs [block] with [count] echo servers listening, each on its own port; a server records which connection is which. */
private suspend fun withEchoServers(
    count: Int,
    ports: List<Int>,
    tls: QuicTlsConfig,
    options: QuicOptions,
    serverScopes: Array<QuicScope?>,
    block: suspend (List<Int>) -> Unit,
): Unit =
    coroutineScope {
        if (count == 0) {
            block(ports)
        } else {
            withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tls, quicOptions = options) {
                println("RCVBUF $udpReceiveBuffers")
                val serverJob =
                    launch {
                        connections {
                            val scope = this
                            coroutineScope { streams().collect { launch { serveEcho(it, scope, serverScopes) } } }
                        }
                    }
                try {
                    withEchoServers(count - 1, ports + port, tls, options, serverScopes, block)
                } finally {
                    serverJob.cancel()
                }
            }
        }
    }

/** Echoes [stream]; its first bytes carry the client's connection number, which names [scope] in [serverScopes]. */
private suspend fun serveEcho(
    stream: QuicByteStream,
    scope: QuicScope,
    serverScopes: Array<QuicScope?>,
) {
    try {
        var named = false
        while (true) {
            when (val r = stream.read(10.minutes)) {
                is ReadResult.Data ->
                    try {
                        if (!named && r.buffer.remaining() > INDEX_DIGITS) {
                            val at = r.buffer.position()
                            val digits = CharArray(INDEX_DIGITS) { r.buffer[at + 1 + it].toInt().toChar() }
                            serverScopes[digits.concatToString().toInt(16)] = scope
                            named = true
                        }
                        stream.writeFully(r.buffer, 30.seconds)
                    } finally {
                        r.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> break
            }
        }
        stream.shutdownSend()
    } catch (_: Exception) {
    } finally {
        stream.close()
    }
}

private suspend fun QuicByteStream.readEcho() {
    var got = 0
    while (got < REQUEST_SIZE) {
        when (val r = read(30.seconds)) {
            is ReadResult.Data ->
                try {
                    got += r.buffer.remaining()
                } finally {
                    r.buffer.freeIfNeeded()
                }
            ReadResult.End, ReadResult.Reset -> return
        }
    }
}

private fun reportProgress(
    firstDone: LongArray,
    opStarted: LongArray,
    nowNs: Long,
) {
    val done = firstDone.filter { it != NOT_YET }.sorted()

    fun at(p: Double) = if (done.isEmpty()) Double.NaN else done[minOf(done.size - 1, (done.size * p).toInt())] / 1e9
    val neverStarted = firstDone.indices.count { firstDone[it] == NOT_YET && opStarted[it] == NOT_YET }
    val waiting =
        firstDone.indices
            .filter { firstDone[it] == NOT_YET && opStarted[it] != NOT_YET }
            .map { (nowNs - opStarted[it]) / 1e9 }
            .sorted()
    println(
        "PROGRESS first_echo_done=${done.size} first_echo_s_p10=${at(0.1)} p50=${at(0.5)} p90=${at(0.9)} max=${at(1.0)} " +
            "coroutine_never_ran=$neverStarted first_echo_outstanding=${waiting.size} " +
            "outstanding_for_s_min=${waiting.firstOrNull() ?: 0.0} max=${waiting.lastOrNull() ?: 0.0}",
    )
}

private suspend fun reportDrivers(
    clients: Array<QuicScope?>,
    servers: Array<QuicScope?>,
    counts: IntArray,
    connectedAfterNanos: LongArray,
) {
    val stuck = counts.indices.filter { counts[it] == 0 }
    println("STUCK-COUNT ${stuck.size}")
    for (i in stuck.take(MAX_DUMPED)) describe("STUCK", i, clients, servers, counts, connectedAfterNanos)
    counts.indices.firstOrNull { counts[it] > 0 }?.let { describe("WORKING", it, clients, servers, counts, connectedAfterNanos) }
}

private suspend fun describe(
    tag: String,
    i: Int,
    clients: Array<QuicScope?>,
    servers: Array<QuicScope?>,
    counts: IntArray,
    connectedAfterNanos: LongArray,
) {
    println("$tag conn=$i echoes_in_window=${counts[i]} connected_after_s=${connectedAfterNanos[i] / 1e9}")
    println("$tag conn=$i client ${snapshotOf(clients[i])}")
    println("$tag conn=$i server ${snapshotOf(servers[i])}")
}

private suspend fun snapshotOf(scope: QuicScope?): String {
    val driver = (scope as? QuicheBackedConnection)?.quicheDriver ?: return "no connection recorded"
    return withTimeoutOrNull(3.seconds) { driver.stallSnapshot().toString() } ?: "the driver loop did not answer in 3 s"
}

private fun reportResult(
    host: ManyConnectionHost,
    conns: Int,
    samples: Array<LongArray>,
    counts: IntArray,
    window: Duration,
    cpuUs: Long,
    connectSeconds: Double,
) {
    val p50s = DoubleArray(conns)
    val p99s = DoubleArray(conns)
    for (i in 0 until conns) {
        val n = counts[i]
        samples[i].sort(0, n)
        p50s[i] = if (n == 0) Double.NaN else samples[i][n / 2] / 1000.0
        p99s[i] = if (n == 0) Double.NaN else samples[i][minOf(n - 1, (n * 0.99).toInt())] / 1000.0
    }
    p50s.sort()
    p99s.sort()
    val total = counts.sum()
    val secs = window.inWholeNanoseconds / 1e9
    println(
        "RESULT stack=${host.stack} workload=qconns$conns ops=$total seconds=$secs p50_us=${p50s[conns / 2]} " +
            "p99_us=${p99s[conns / 2]} p999_us=${p99s[conns - 1]} ops_per_s=${total / secs} mib_per_s=0.0 " +
            "cpu_us=$cpuUs maxrss_raw=${host.maxRssRaw()} idle_conns=${counts.count { it == 0 }} connect_s=$connectSeconds",
    )
}

private const val REQUEST_SIZE = 64
private const val INDEX_DIGITS = 8
private const val ECHO: Byte = 'E'.code.toByte()
private const val NOT_YET = -1L
private const val MAX_DUMPED = 40
