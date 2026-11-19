package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.CoreClient
import com.ditchoom.socket.quic.core.edge.CoreServer
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.handleStreams
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/*
 * Repeatable 1 KiB echo load for profiling the run-to-completion core (main class, not a test).
 *
 *   both   <conns,..> <warmS> <measureS> [bind]          client + server in this JVM
 *   server <port> [bind] [stats-file]                    echo server until killed; cumulative stats every 500 ms
 *   client <host> <port> <conns,..> <warmS> <measureS>   clients only
 *
 * bind: v4 (127.0.0.1, default), v6 (::1), any (wildcard, dual-stack). One line per cell:
 *   PROFILE <role> M=.. ops/s=.. cpu_us/op=.. plus per-echo counters (loop passes, waits, datagrams in/out,
 *   caller sends in place, loop wakes by callers) and per-thread-group CPU per echo.
 * Options (system properties): -DclientDispatch=unconfined / -DserverDispatch=unconfined run the echo callers / handlers
 * on the thread that resumes them (the loop), -Dack=across holds the server's ACKs across turns.
 * Launch with the jvmTest classpath (`:socket-quic-quiche:netnsQuicProbeClasspath` writes it) after
 * `generateLocalhostCert jvmTestProcessResources stageQuicheNativeResources`.
 * The measured window is marked with WINDOW-BEGIN / WINDOW-END epoch-ms lines so a profiler started with a delay,
 * or a separate server's stats file, can be cut to it.
 */

private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
private val threads = ManagementFactory.getThreadMXBean()
private const val PAYLOAD = 1_024

/** -DclientDispatch=unconfined runs the echo callers where they are resumed (the loop thread): no hop. */
private val clientDispatcher: kotlinx.coroutines.CoroutineDispatcher =
    if (System.getProperty("clientDispatch") == "unconfined") Dispatchers.Unconfined else Dispatchers.Default

/** -DserverDispatch=unconfined runs the echo handlers on the server loop thread; -Dack=across holds ACKs across turns. */
private suspend fun profileEchoServer(
    bind: PathKey,
    body: suspend kotlinx.coroutines.CoroutineScope.(CoreServer) -> Unit,
) {
    val config =
        if (System.getProperty("ack") == "across") {
            ServerConfig(
                ack =
                    com.ditchoom.socket.quic.core.conn.AckRule
                        .acrossTurns(
                            com.ditchoom.socket.quic.core.time
                                .WaitResolution(1_000_000L),
                        ),
            )
        } else {
            ServerConfig()
        }
    val placement: com.ditchoom.socket.quic.core.sched.HandlerPlacement =
        when (System.getProperty("serverDispatch")) {
            "onloop" -> com.ditchoom.socket.quic.core.sched.HandlerPlacement.OnLoop
            "unconfined" ->
                com.ditchoom.socket.quic.core.sched.HandlerPlacement
                    .OffLoop(Dispatchers.Unconfined)
            else ->
                com.ditchoom.socket.quic.core.sched.HandlerPlacement
                    .OffLoop(Dispatchers.Default)
        }
    val server =
        com.ditchoom.socket.quic.core.edge.coreServe(
            mismatch = com.ditchoom.socket.quic.core.conn.MismatchResponse.Fail,
            bind = bind,
            tls =
                com.ditchoom.socket.quic.core.testing
                    .platformTlsConfig(),
            options = com.ditchoom.socket.quic.core.edge.coreTestOptions,
            factory = BufferFactory.network(),
            api =
                com.ditchoom.socket.quic.core.testing
                    .platformQuicheApi(),
            readPolicy =
                com.ditchoom.buffer.flow.ReadPolicy
                    .Bounded(10.seconds),
            writePolicy =
                com.ditchoom.buffer.flow.WritePolicy
                    .Bounded(10.seconds),
            config = config,
            wrapIo = { it },
        )
    try {
        coroutineScope {
            server.loop.port.handlers
                .observe(stallSink)
            val serving =
                launch(Dispatchers.Default) {
                    runCatching {
                        server.connections().collect { connection ->
                            connection.handleStreams(placement) { stream -> serveStream(stream) }
                        }
                    }
                }
            body(server)
            serving.cancel()
        }
    } finally {
        server.close()
    }
}

/** What the bad connection's handler does per request (-Dbad=burn|sleep|offload). */
private val bad = System.getProperty("bad") ?: "none"
private const val POISON: Byte = 0x55
private const val BAD_NANOS = 5_000_000L

private fun burn(nanos: Long) {
    val end = System.nanoTime() + nanos
    while (System.nanoTime() < end) Unit
}

/** Stall observations: count and the sites named, read at the end of a cell. */
private val stallCount = AtomicLong()
private val stallMaxNanos = AtomicLong()
private val stallSites = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val stallSink =
    com.ditchoom.socket.quic.core.sched.HandlerSink { o ->
        when (o) {
            is com.ditchoom.socket.quic.core.sched.HandlerObservation.Stalled -> {
                stallCount.incrementAndGet()
                stallMaxNanos.accumulateAndGet(o.heldNanos) { a, b -> maxOf(a, b) }
                stallSites.merge(o.site.toString(), 1L, Long::plus)
            }
            is com.ditchoom.socket.quic.core.sched.HandlerObservation.Failed -> println("HANDLER-FAILED ${o.site}")
        }
    }

/** -Dbulk=up: read and drop until the end, then answer one byte; -Dbulk=down: send -DbulkMiB MiB; else echo. */
private suspend fun serveStream(stream: QuicByteStream) {
    when (System.getProperty("bulk")) {
        "up" -> {
            while (true) {
                val r = stream.read(30.seconds)
                if (r !is ReadResult.Data) break
                r.buffer.freeIfNeeded()
            }
            stream.writeFully(
                BufferFactory.network().allocate(1).apply {
                    writeByte(1)
                    resetForRead()
                },
                30.seconds,
            )
            stream.close()
        }
        "down" -> {
            val first = stream.read(30.seconds)
            if (first is ReadResult.Data) first.buffer.freeIfNeeded()
            val chunk = bulkChunk()
            repeat(bulkMiB * (1 shl 20) / BULK_CHUNK) {
                chunk.position(0)
                stream.writeFully(chunk, 30.seconds)
            }
            stream.close()
        }
        else -> {
            while (stream.read(10.seconds) {
                    if (it.get(it.position()) == POISON) {
                        when (bad) {
                            "burn" -> burn(BAD_NANOS)
                            "sleep" -> Thread.sleep(BAD_NANOS / 1_000_000)
                            "offload" -> kotlinx.coroutines.withContext(Dispatchers.Default) { burn(BAD_NANOS) }
                        }
                    }
                    stream.writeFully(it, 10.seconds)
                } is com.ditchoom.socket.quic.ScopedRead.Data<*>
            ) {
                Unit
            }
            stream.close()
        }
    }
}

private val bulkMiB = (System.getProperty("bulkMiB") ?: "100").toInt()
private const val BULK_CHUNK = 64 * 1024

private fun bulkChunk(): PlatformBuffer =
    BufferFactory.network().allocate(BULK_CHUNK).apply {
        repeat(BULK_CHUNK) { writeByte(it.toByte()) }
        resetForRead()
    }

private fun bindOf(
    name: String,
    port: Int,
): PathKey =
    when (name) {
        "v6" -> PathKey(6, port, 0L, 1L)
        "any" -> wildcardV4(port)
        else -> loopbackV4(port)
    }

/** CPU nanoseconds per thread group: loop threads, coroutine workers, and the rest (JIT, GC, ...). */
private fun threadCpu(): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (info in threads.getThreadInfo(threads.allThreadIds)) {
        if (info == null) continue
        val cpu = threads.getThreadCpuTime(info.threadId)
        if (cpu < 0) continue
        val group =
            when {
                info.threadName.startsWith("quic-core-client") -> "clientLoop"
                info.threadName.startsWith("quic-core") -> "serverLoop:" + info.threadName
                info.threadName.startsWith("DefaultDispatcher") -> "coroutineWorkers"
                info.threadName.contains("GC") || info.threadName.contains("G1") -> "gc"
                info.threadName.contains("Compiler") -> "jit"
                else -> "other:" + info.threadName
            }
        out[group] = (out[group] ?: 0L) + cpu
    }
    return out
}

/** macOS `top` per-process context switches and BSD/Mach syscalls; Linux /proc ctxt switches. Cumulative. */
private fun processKernelCounters(): LongArray {
    val pid = ProcessHandle.current().pid()
    return runCatching {
        if (File("/proc/self/status").exists()) {
            var vol = 0L
            var invol = 0L
            File("/proc/self/task").listFiles()?.forEach { task ->
                runCatching {
                    File(task, "status").readLines().forEach {
                        if (it.startsWith("voluntary_ctxt_switches")) vol += it.split(Regex("\\s+"))[1].toLong()
                        if (it.startsWith("nonvoluntary_ctxt_switches")) invol += it.split(Regex("\\s+"))[1].toLong()
                    }
                }
            }
            longArrayOf(vol + invol, -1, -1)
        } else {
            val p = ProcessBuilder("top", "-l", "1", "-pid", "$pid", "-stats", "pid,csw,sysbsd,sysmach").start()
            val line =
                p.inputStream
                    .bufferedReader()
                    .readLines()
                    .last()
                    .trim()
                    .split(Regex("\\s+"))
            p.waitFor()
            longArrayOf(line[1].trimEnd('+').toLong(), line[2].trimEnd('+').toLong(), line[3].trimEnd('+').toLong())
        }
    }.getOrElse { longArrayOf(-1, -1, -1) }
}

private class Snapshot(
    val cpu: Long,
    val nanos: Long,
    val allocated: Long,
    val serverLoopAllocated: Long,
    val threadCpu: Map<String, Long>,
    val kernel: LongArray,
    val loops: List<LongArray>,
)

/** passes, waits, datagramsIn, datagramsOut, work, caller sendsInPlace, caller datagramsInPlace, loopWakes */
private fun loopCounters(loop: Loop): LongArray =
    longArrayOf(
        loop.counts.passes,
        loop.counts.waits,
        loop.counts.datagramsIn,
        loop.counts.datagramsOut,
        loop.counts.work,
        loop.callerCounts.sendsInPlace,
        loop.callerCounts.datagramsInPlace,
        loop.callerCounts.loopWakes,
    )

private val LOOP_FIELDS = listOf("passes", "waits", "dgIn", "dgOut", "work", "callerSends", "callerDg", "loopWakes")

private val allocThreads = threads as com.sun.management.ThreadMXBean

private fun allocated(filter: (String) -> Boolean): Long {
    var sum = 0L
    for (info in threads.getThreadInfo(threads.allThreadIds)) {
        if (info == null || !filter(info.threadName)) continue
        val a = allocThreads.getThreadAllocatedBytes(info.threadId)
        if (a > 0) sum += a
    }
    return sum
}

private fun snapshot(loops: List<Loop>): Snapshot =
    Snapshot(
        os.processCpuTime,
        System.nanoTime(),
        allocated { true },
        allocated { it == "quic-core-server" },
        threadCpu(),
        processKernelCounters(),
        loops.map { loopCounters(it) },
    )

private fun report(
    role: String,
    m: Int,
    ops: Long,
    a: Snapshot,
    b: Snapshot,
    loopNames: List<String>,
) {
    val wall = b.nanos - a.nanos
    val cpu = b.cpu - a.cpu
    val sb = StringBuilder()
    sb.append(
        "PROFILE %s M=%d ops=%d ops/s=%.0f cpu_us/op=%.2f cores=%.2f".format(
            role,
            m,
            ops,
            ops * 1e9 / wall,
            cpu / 1e3 / ops,
            cpu.toDouble() / wall,
        ),
    )
    sb.append(
        " alloc_B/op=%.0f serverLoopAlloc_B/op=%.1f".format(
            (b.allocated - a.allocated).toDouble() / ops,
            (b.serverLoopAllocated - a.serverLoopAllocated).toDouble() / ops,
        ),
    )
    val kc = LongArray(3) { b.kernel[it] - a.kernel[it] }
    sb.append(" csw/op=%.2f sysbsd/op=%.2f sysmach/op=%.2f".format(kc[0].toDouble() / ops, kc[1].toDouble() / ops, kc[2].toDouble() / ops))
    // Loop counters summed per name (client loops summed together).
    val sums = LinkedHashMap<String, LongArray>()
    for (i in loopNames.indices) {
        val acc = sums.getOrPut(loopNames[i]) { LongArray(LOOP_FIELDS.size) }
        for (f in LOOP_FIELDS.indices) acc[f] += b.loops[i][f] - a.loops[i][f]
    }
    for ((name, acc) in sums) {
        sb.append("\n  loop[$name]/op:")
        for (f in LOOP_FIELDS.indices) sb.append(" %s=%.3f".format(LOOP_FIELDS[f], acc[f].toDouble() / ops))
    }
    sb.append("\n  threadCpu_us/op:")
    for (k in (a.threadCpu.keys + b.threadCpu.keys).toSortedSet()) {
        val d = (b.threadCpu[k] ?: 0L) - (a.threadCpu[k] ?: 0L)
        if (d > ops * 100) sb.append(" %s=%.2f".format(k, d / 1e3 / ops))
    }
    println(sb)
}

/** Per-echo latencies of each connection in the last measured window, nanoseconds. */
private class Latencies(
    connections: Int,
) {
    val each = Array(connections) { LongArray(1 shl 16) }
    val counts = IntArray(connections)

    fun record(
        i: Int,
        nanos: Long,
    ) {
        val n = counts[i]
        val arr = each[i]
        arr[n and (arr.size - 1)] = nanos
        counts[i] = n + 1
    }

    fun percentiles(skip: Int): String {
        val all = ArrayList<Long>()
        for (i in each.indices) {
            if (i == skip) continue
            val n = minOf(counts[i], each[i].size)
            for (k in 0 until n) all.add(each[i][k])
        }
        if (all.isEmpty()) return "p50_us=- p99_us=-"
        all.sort()

        fun at(q: Double) = all[minOf(all.size - 1, (q * all.size).toInt())] / 1e3
        return "p50_us=%.1f p99_us=%.1f p999_us=%.1f".format(at(0.50), at(0.99), at(0.999))
    }
}

private var lastLatencies = Latencies(0)

private suspend fun runEchoes(
    clients: List<CoreClient>,
    ms: Long,
): Long {
    val streams: List<QuicByteStream> = clients.map { it.connection.openStream() }
    val badIndex = if (bad == "none") -1 else 0
    val payloads = List(clients.size) { payload(if (it == badIndex) POISON else 0) }
    val ops = AtomicLong()
    val latencies = Latencies(clients.size)
    coroutineScope {
        val end = System.nanoTime() + ms * 1_000_000
        streams.indices
            .map { i ->
                async(clientDispatcher) {
                    while (System.nanoTime() < end) {
                        val began = System.nanoTime()
                        roundTrip(streams[i], payloads[i])
                        latencies.record(i, System.nanoTime() - began)
                        if (i != badIndex) ops.incrementAndGet()
                    }
                }
            }.awaitAll()
    }
    lastLatencies = latencies
    return ops.get()
}

private fun payload(first: Byte): PlatformBuffer =
    BufferFactory.network().allocate(PAYLOAD).apply {
        writeByte(first)
        repeat(PAYLOAD - 1) { writeByte((it + 1).toByte()) }
        resetForRead()
    }

private suspend fun roundTrip(
    stream: QuicByteStream,
    payload: PlatformBuffer,
) {
    payload.position(0)
    stream.write(payload, 10.seconds)
    var received = 0
    while (received < payload.limit()) {
        val result = stream.read(10.seconds)
        if (result !is ReadResult.Data) error("the echo ended early: $result")
        received += result.buffer.remaining()
        result.buffer.freeIfNeeded()
    }
}

/** Runs one cell of [m] connections; returns after printing. [server] is null when the server is another process. */
private suspend fun cell(
    host: String,
    port: Int,
    m: Int,
    warmS: Long,
    measureS: Long,
    server: CoreServer?,
) {
    val loops = LoopSet(2)
    try {
        val clients = coroutineScope { List(m) { async { connectCore(port, host = host, loops = loops) } }.awaitAll() }
        val clientLoops = clients.map { it.loop }.distinct()
        val all = clientLoops + listOfNotNull(server?.loop)
        val names = clientLoops.map { "client" } + listOfNotNull(server?.let { "server" })
        // Warm-up and measure share one stream per connection: a fresh runEchoes opens new streams, so warm separately.
        runEchoes(clients, warmS * 1000)
        val a = snapshot(all)
        println("WINDOW-BEGIN ${System.currentTimeMillis()} M=$m")
        val ops = runEchoes(clients, measureS * 1000)
        println("WINDOW-END ${System.currentTimeMillis()} M=$m")
        val b = snapshot(all)
        report(if (server != null) "both" else "client", m, ops, a, b, names)
        val skip = if (bad == "none") -1 else 0
        val extra =
            if (server != null) {
                val h = server.loop.port.handlers
                " handlerRuns=%d longestRun_us=%.0f longestServerPass_us=%.0f".format(
                    h.runs,
                    h.longestRunNanos / 1e3,
                    server.loop.counts.longestPassNanos / 1e3,
                )
            } else {
                ""
            }
        println(
            "  latency(good conns): ${lastLatencies.percentiles(skip)} stalls=${stallCount.getAndSet(0)} " +
                "stallMax_us=%.0f".format(stallMaxNanos.getAndSet(0) / 1e3) +
                " stallSites=${stallSites.entries.sortedByDescending { it.value }.take(3).map { "${it.key.takeLast(12)}:${it.value}" }}" +
                extra,
        )
        stallSites.clear()
        clients.forEach { runCatching { it.connection.close(QuicError.NoError) } }
        delay(300)
    } finally {
        loops.close()
    }
}

fun main(args: Array<String>) {
    println(
        "JVM ${System.getProperty(
            "java.version",
        )} backend=${System.getProperty("quiche.expectedJvmBackend")} api=${
            com.ditchoom.socket.quic.core.testing
                .platformQuicheApi()::class.simpleName
        } pid=${ProcessHandle.current().pid()}",
    )
    when (args[0]) {
        "both" -> {
            val conns = args[1].split(",").map { it.toInt() }
            val bind = args.getOrElse(4) { "v4" }
            val host = if (bind == "v6") "::1" else "127.0.0.1"
            runBlocking {
                profileEchoServer(bindOf(bind, 0)) { server ->
                    for (m in conns) cell(host, server.port, m, args[2].toLong(), args[3].toLong(), server)
                }
            }
        }
        "bulk" -> runBlocking { bulk(args[1].toInt()) }
        "server" -> {
            val port = args[1].toInt()
            val bind = args.getOrElse(2) { "v4" }
            val stats = args.getOrNull(3)?.let { File(it) }
            runBlocking {
                profileEchoServer(bindOf(bind, port)) { server ->
                    println("PORT ${server.port}")
                    val out = stats?.printWriter()
                    while (true) {
                        val s = snapshot(listOf(server.loop))
                        out?.println(
                            "S ${System.currentTimeMillis()} cpu=${s.cpu} kernel=${s.kernel.joinToString(
                                ",",
                            )} loop=${s.loops[0].joinToString(",")} " +
                                s.threadCpu.entries.joinToString(" ") { "${it.key.replace(' ', '_')}=${it.value}" },
                        )
                        out?.flush()
                        delay(500)
                    }
                }
            }
        }
        "client" -> {
            val host = args[1]
            val port = args[2].toInt()
            val conns = args[3].split(",").map { it.toInt() }
            runBlocking { for (m in conns) cell(host, port, m, args[4].toLong(), args[5].toLong(), null) }
        }
    }
    System.exit(0)
}

/** One connection, one stream per run: -Dbulk=up uploads -DbulkMiB MiB, -Dbulk=down downloads them. */
private suspend fun bulk(repeats: Int) {
    profileEchoServer(bindOf("v4", 0)) { server ->
        val loops = LoopSet(1)
        try {
            val client = connectCore(server.port, host = "127.0.0.1", loops = loops)
            val all = listOf(client.loop, server.loop)
            val chunk = bulkChunk()
            repeat(repeats + 1) { run ->
                val a = snapshot(all)
                val stream = client.connection.openStream()
                val began = System.nanoTime()
                when (System.getProperty("bulk")) {
                    "up" -> {
                        repeat(bulkMiB * (1 shl 20) / BULK_CHUNK) {
                            chunk.position(0)
                            stream.writeFully(chunk, 30.seconds)
                        }
                        stream.shutdownSend()
                        val r = stream.read(30.seconds)
                        if (r is ReadResult.Data) r.buffer.freeIfNeeded()
                    }
                    else -> {
                        stream.writeFully(payload(1), 30.seconds)
                        var got = 0L
                        while (true) {
                            val r = stream.read(30.seconds)
                            if (r !is ReadResult.Data) break
                            got += r.buffer.remaining()
                            r.buffer.freeIfNeeded()
                        }
                        check(got == bulkMiB.toLong() shl 20) { "got $got" }
                    }
                }
                val wall = System.nanoTime() - began
                val b = snapshot(all)
                stream.close()
                val h = server.loop.port.handlers
                val serverLoop = "serverLoop:quic-core-server"
                if (run > 0) {
                    println(
                        (
                            "BULK %s run=%d MiB=%d MiB/s=%.0f cpu_us/MiB=%.0f serverLoopCpu_us/MiB=%.0f " +
                                "longestHandlerRun_us=%.0f longestServerPass_us=%.0f stalls=%d"
                        ).format(
                            System.getProperty("bulk"),
                            run,
                            bulkMiB,
                            bulkMiB * 1e9 / wall,
                            (b.cpu - a.cpu) / 1e3 / bulkMiB,
                            ((b.threadCpu[serverLoop] ?: 0L) - (a.threadCpu[serverLoop] ?: 0L)) / 1e3 / bulkMiB,
                            h.longestRunNanos / 1e3,
                            server.loop.counts.longestPassNanos / 1e3,
                            stallCount.getAndSet(0),
                        ),
                    )
                }
                delay(200)
            }
            client.connection.close(QuicError.NoError)
        } finally {
            loops.close()
        }
    }
}
