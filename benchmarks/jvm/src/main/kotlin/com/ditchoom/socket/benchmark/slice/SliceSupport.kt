package com.ditchoom.socket.benchmark.slice

import com.ditchoom.socket.benchmark.ECHO_MARKER
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.loadQuicheApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext

private const val QUIC_V1 = 0x00000001
private const val CUBIC = 1

private fun cString(s: String): NativeBuf {
    val buf = NativeBuf(s.length + 1)
    s.forEachIndexed { i, ch -> buf.bb.put(i, ch.code.toByte()) }
    buf.bb.put(s.length, 0)
    return buf
}

/** The settings QuicLoopback.OPTIONS resolves to, applied by hand (the library's applicator is internal). */
internal fun sliceConfig(
    api: QuicheApi,
    server: Boolean,
): QuicheConfig {
    val config = api.configNew(QUIC_V1)
    if (server) {
        val dir = System.getProperty("bench.certDir") ?: error("bench.certDir is not set")
        check(api.configLoadCertChainFromPemFile(config, cString(File(dir, "cert.crt").path).address) == 0)
        check(api.configLoadPrivKeyFromPemFile(config, cString(File(dir, "cert.key").path).address) == 0)
    }
    val alpn = NativeBuf(6)
    alpn.bb.put(0, 5)
    "bench".forEachIndexed { i, ch -> alpn.bb.put(1 + i, ch.code.toByte()) }
    api.configSetApplicationProtos(config, alpn.address, 6)
    api.configSetMaxIdleTimeout(config, 600_000)
    api.configSetMaxRecvUdpPayloadSize(config, 1350)
    api.configSetMaxSendUdpPayloadSize(config, 1350)
    api.configSetInitialMaxData(config, 64L * 1024 * 1024)
    api.configSetInitialMaxStreamDataBidiLocal(config, 16L * 1024 * 1024)
    api.configSetInitialMaxStreamDataBidiRemote(config, 16L * 1024 * 1024)
    api.configSetInitialMaxStreamDataUni(config, 1_048_576)
    api.configSetInitialMaxStreamsBidi(config, 1_000_000)
    api.configSetInitialMaxStreamsUni(config, 100)
    api.configSetDisableActiveMigration(config, true)
    api.configSetActiveConnectionIdLimit(config, 8)
    api.configVerifyPeer(config, false)
    api.configSetCcAlgorithm(config, CUBIC)
    api.configEnableHystart(config, true)
    api.configEnablePacing(config, true)
    api.configDiscoverPmtu(config, false)
    api.configGrease(config, true)
    return config
}

/** The handler of QuicLoopback.serveStream: echo a stream that starts with ECHO_MARKER, drain any other. */
internal suspend fun serveStream(stream: SliceStream) {
    var first = true
    var echo = false
    while (true) {
        when (val r = stream.read()) {
            is NativeBuf ->
                try {
                    if (first) {
                        first = false
                        echo = r.remaining > 0 && r.bb.get(r.position) == ECHO_MARKER
                    }
                    if (echo) stream.write(r)
                } finally {
                    stream.release(r)
                }
            SliceEnd -> break
        }
    }
    stream.shutdownSend()
}

/**
 * How long a resume handed to a handler thread waited before that thread ran it, and whether a thread had to
 * be woken for it. Buckets of half a microsecond up to a millisecond.
 */
internal class HandoffProbe {
    private val buckets = Array(BUCKETS) { LongAdder() }
    val woke = LongAdder()
    val foundRunning = LongAdder()

    fun record(nanos: Long) {
        buckets[(nanos / 500).coerceIn(0, BUCKETS - 1L).toInt()].increment()
    }

    private fun percentile(p: Double): Double {
        val total = buckets.sumOf { it.sum() }
        var seen = 0L
        for (i in 0 until BUCKETS) {
            seen += buckets[i].sum()
            if (seen >= total * p) return (i + 1) * 0.5
        }
        return BUCKETS * 0.5
    }

    fun report(): String {
        val total = buckets.sumOf { it.sum() }
        if (total == 0L) return "# hand-off probe: no hand-offs"
        val w = woke.sum()
        val r = foundRunning.sum()
        val parked = if (w + r == 0L) "n/a (a hand-off pool always gives the resume to a parked thread or starts one)" else "%.1f%%".format(100.0 * w / (w + r))
        return "# hand-off probe: $total hand-offs, wait p50 %.1f us p90 %.1f us p99 %.1f us, found the thread parked: %s"
            .format(percentile(0.50), percentile(0.90), percentile(0.99), parked)
    }

    private companion object {
        const val BUCKETS = 2000
    }
}

private class Timed(
    private val block: Runnable,
    private val probe: HandoffProbe,
) : Runnable {
    private val at = System.nanoTime()

    override fun run() {
        probe.record(System.nanoTime() - at)
        block.run()
    }
}

/** The threads a server runs its handlers on. */
internal sealed interface HandlerThreads : AutoCloseable {
    val dispatcher: CoroutineDispatcher

    fun threads(): List<Thread>
}

/**
 * The pool of the library's draft "handlers on owned threads": every resume is handed to a thread that is
 * parked waiting for one (the most recently parked), or to a new thread when none is.
 */
internal class HandOffThreads(
    private val probe: HandoffProbe?,
) : CoroutineDispatcher(),
    HandlerThreads {
    private val started = ConcurrentLinkedQueue<Thread>()
    private val n = AtomicInteger()
    private val pool =
        ThreadPoolExecutor(
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8),
            256,
            10,
            TimeUnit.SECONDS,
            SynchronousQueue(),
        ) { r ->
            Thread(r, "slice-handler-${n.incrementAndGet()}").also {
                it.isDaemon = true
                started.add(it)
            }
        }

    init {
        pool.prestartAllCoreThreads()
    }

    private val inner = pool.asCoroutineDispatcher()
    override val dispatcher: CoroutineDispatcher get() = this

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = inner.dispatch(context, if (probe == null) block else Timed(block, probe))

    override fun threads(): List<Thread> = started.toList()

    override fun close() {
        pool.shutdownNow()
    }
}

private const val RUNNING = 0
private const val PARKED = 1
private const val NOTIFIED = 2

/**
 * A fixed set of threads over one lock-free queue: a resume is queued, and a parked thread is woken for it
 * only if there is one; a thread that is running takes the next resume without parking.
 */
internal class QueueThreads(
    size: Int,
    private val probe: HandoffProbe?,
) : CoroutineDispatcher(),
    HandlerThreads {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    @Volatile private var running = true
    private val workers = Array(size) { Worker(it + 1) }

    override val dispatcher: CoroutineDispatcher get() = this

    init {
        workers.forEach { it.thread.start() }
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.add(if (probe == null) block else Timed(block, probe))
        for (w in workers) {
            if (w.state.get() == PARKED && w.state.compareAndSet(PARKED, NOTIFIED)) {
                probe?.woke?.increment()
                LockSupport.unpark(w.thread)
                return
            }
        }
        probe?.foundRunning?.increment()
    }

    override fun threads(): List<Thread> = workers.map { it.thread }

    override fun close() {
        running = false
        workers.forEach { LockSupport.unpark(it.thread) }
    }

    private inner class Worker(
        index: Int,
    ) {
        val state = AtomicInteger(RUNNING)
        val thread = Thread(::run, "slice-handler-q$index").also { it.isDaemon = true }

        private fun run() {
            while (running) {
                var task = queue.poll()
                if (task == null) {
                    state.set(PARKED)
                    task = queue.poll()
                    if (task == null) {
                        while (state.get() == PARKED && running) LockSupport.park(this)
                        state.set(RUNNING)
                        continue
                    }
                    state.set(RUNNING)
                }
                task.run()
            }
        }
    }
}

/**
 * One slice server on 127.0.0.1 and [connectionCount] client connections to it. The server has one loop
 * thread (as a Netty QUIC server has one event loop per UDP socket); the clients share [clientLoops].
 *
 * placement: `loop` (handler on the server's loop), `owned` (hand-off pool), `ownedq` (queue pool).
 */
internal class SliceLoopback(
    placementName: String,
    holdAck: Boolean,
    callerRuns: Boolean,
    connectionCount: Int = 1,
    clientLoops: Int = 1,
) : AutoCloseable {
    private val api = loadQuicheApi()
    private val settings = SliceSettings(holdAck, callerRuns)
    val probe: HandoffProbe? = if (System.getProperty("slice.probe") == "true") HandoffProbe() else null
    private val handlerThreads: HandlerThreads? =
        when (placementName) {
            "loop" -> null
            "owned" -> HandOffThreads(probe)
            "ownedq" -> QueueThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8), probe)
            else -> error("placement must be loop, owned or ownedq, was $placementName")
        }
    private val serverLoop: SliceLoop
    private val clients: List<SliceLoop>
    val connections: List<SliceConn>
    val connection: SliceConn get() = connections[0]

    init {
        val placement = if (handlerThreads == null) HandlerPlacement.OnLoop else HandlerPlacement.Owned(handlerThreads.dispatcher)
        val serverChannel = openUdp()
        serverLoop = SliceLoop("slice-server-loop", api, sliceConfig(api, server = true), settings, placement, ::serveStream)
        serverLoop.start()
        serverLoop.serve(serverChannel)
        val serverAddress = serverChannel.localAddress as InetSocketAddress
        val clientConfig = sliceConfig(api, server = false)
        clients =
            List(clientLoops) { i ->
                SliceLoop("slice-client-loop-${i + 1}", api, clientConfig, settings, HandlerPlacement.OnLoop) {}.also { it.start() }
            }
        connections = List(connectionCount) { i -> clients[i % clientLoops].connect(serverAddress) }
    }

    fun serverThreads(): List<Thread> = listOf(serverLoop.thread) + (handlerThreads?.threads() ?: emptyList())

    /** Per-operation counts over everything since the loops started, both endpoints together. */
    fun report(ops: Long): String {
        val s = serverLoop.counts
        val o = ops.coerceAtLeast(1).toDouble()

        fun per(v: Long) = "%.2f".format(v / o)

        fun clientSum(f: (SliceCounts) -> LongAdder) = clients.sumOf { f(it.counts).sum() }

        fun both(f: (SliceCounts) -> LongAdder) = f(s).sum() + clientSum(f)
        val text =
            "# slice counts per op over $ops ops: datagrams ${per(both { it.datagramsSent })}, " +
                "conn_send ${per(both { it.connSendCalls })}, conn_recv ${per(both { it.connRecvCalls })}, " +
                "loop sleeps server ${per(s.blockingSelects.sum())} client ${per(clientSum { it.blockingSelects })}, " +
                "resumes across threads server ${per(s.resumesAcrossThreads.sum())} client ${per(clientSum { it.resumesAcrossThreads })}, " +
                "loop tasks server ${per(s.tasks.sum())} client ${per(clientSum { it.tasks })}, " +
                "entered by caller server ${per(s.enteredByCaller.sum())} client ${per(clientSum { it.enteredByCaller })}, " +
                "left for the thread inside ${per(both { it.handedToOwner })}, " +
                "held flushes ${per(both { it.heldFlushes })}, send drops ${both { it.sendDrops }}, " +
                "send errors ${both { it.sendErrors }}, violations ${both { it.violations }}"
        return if (probe == null) text else text + "\n" + probe.report()
    }

    fun violations(): Long = serverLoop.counts.violations.sum() + clients.sumOf { it.counts.violations.sum() }

    override fun close() {
        clients.forEach { it.close() }
        serverLoop.close()
        handlerThreads?.close()
    }
}

internal fun filledRequest(size: Int): NativeBuf {
    val buf = NativeBuf(size)
    buf.bb.put(0, ECHO_MARKER)
    for (i in 1 until size) buf.bb.put(i, 'r'.code.toByte())
    buf.limit = size
    return buf
}

internal suspend fun SliceStream.readExactly(expected: Int): Int {
    var got = 0
    while (got < expected) {
        when (val r = read()) {
            is NativeBuf -> {
                got += r.remaining
                release(r)
            }
            SliceEnd -> return got
        }
    }
    return got
}

internal suspend fun SliceStream.readToEnd(): Int {
    var got = 0
    while (true) {
        when (val r = read()) {
            is NativeBuf -> {
                got += r.remaining
                release(r)
            }
            SliceEnd -> return got
        }
    }
}
