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

/** A stream whose first byte is this is echoed by a handler that blocks its thread for [BLOCK_MILLIS] per chunk. */
internal const val BLOCK_MARKER: Byte = 'B'.code.toByte()
internal const val BLOCK_MILLIS = 100L

/** The handler of QuicLoopback.serveStream: echo a stream that starts with ECHO_MARKER, drain any other. */
internal suspend fun serveStream(stream: SliceStream) {
    var first = true
    var echo = false
    var blocking = false
    while (true) {
        val r =
            when (val result = stream.read()) {
                is NativeBuf -> result
                is SliceData -> result.buffer
                SliceEnd -> break
            }
        run {
                try {
                    if (first) {
                        first = false
                        val b = if (r.remaining > 0) r.bb.get(r.position) else 0
                        blocking = b == BLOCK_MARKER
                        echo = b == ECHO_MARKER || blocking
                    }
                    if (blocking) Thread.sleep(BLOCK_MILLIS)
                    if (echo) stream.write(r)
                } finally {
                    stream.release(r)
                }
        }
    }
    stream.shutdownSend()
}

/** The same handler over writes that are handed to the driver with their buffer and do not wait. */
internal suspend fun serveStreamHandingOver(stream: SliceStream) {
    var first = true
    var echo = false
    var blocking = false
    while (true) {
        val r =
            when (val result = stream.read()) {
                is NativeBuf -> result
                is SliceData -> result.buffer
                SliceEnd -> break
            }
        if (first) {
            first = false
            val b = if (r.remaining > 0) r.bb.get(r.position) else 0
            blocking = b == BLOCK_MARKER
            echo = b == ECHO_MARKER || blocking
        }
        if (blocking) Thread.sleep(BLOCK_MILLIS)
        if (echo) stream.send(r) else stream.release(r)
    }
    stream.finish()
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

    /** Times a handler thread was woken for work. */
    val wakes: LongAdder

    val hooks: LoopHooks get() = NoHooks

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
    override val wakes = LongAdder()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        // Every hand-off of this pool goes to a parked thread, or starts one.
        wakes.increment()
        inner.dispatch(context, if (probe == null) block else Timed(block, probe))
    }

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
    override val wakes = LongAdder()

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
                wakes.increment()
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

private const val STALL_NANOS = 1_000_000L
private val SPIN_WHILE_LOOP_AWAKE = System.getProperty("slice.spinWhileLoopAwake") != "false"
private const val BATCH_CHUNK = 32

/**
 * Handler threads that belong to one loop, in one of two shapes.
 *
 * batched: the loop keeps the resumes one pass produced and hands them over at the end of the pass, at most
 * [BATCH_CHUNK] to a thread, waking each thread once; a handler's writes wait in its thread's batch and reach
 * the loop as one task when the batch is done.
 *
 * affine (not batched): one of the threads is the loop's own. A resume goes to it at once; it spins for
 * [spinNanos] after its last piece of work before it parks, so while the loop is busy it is never woken.
 *
 * Either way a handler that blocks does not hold the others: a thread that has been in one resume for more
 * than [STALL_NANOS] is passed over, and what waits behind it is taken by another thread.
 */
internal class LoopHandlers(
    private val batched: Boolean,
    size: Int,
    private val spinNanos: Long,
) : CoroutineDispatcher(),
    HandlerThreads,
    LoopHooks {
    private lateinit var loop: SliceLoop
    private val workers = Array(size) { Worker(it) }
    private val passResumes = ArrayList<Runnable>()
    private val outstanding = AtomicInteger()

    @Volatile private var primary = 0

    @Volatile private var running = true
    override val dispatcher: CoroutineDispatcher get() = this
    override val wakes = LongAdder()
    override val hooks: LoopHooks get() = this
    val steals = LongAdder()

    override fun attach(loop: SliceLoop) {
        this.loop = loop
        workers.forEach { it.start() }
    }

    override fun outstanding(): Int = if (batched) 0 else outstanding.get()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        outstanding.incrementAndGet()
        if (batched && loop.isLoopThread) {
            passResumes.add(block)
            return
        }
        val w = pick()
        w.inbox.add(block)
        wake(w)
    }

    /** The thread a resume goes to now: the loop's own, unless it is stuck in a resume. */
    private fun pick(): Worker {
        val now = System.nanoTime()
        var i = primary
        repeat(workers.size) {
            val w = workers[i]
            if (!w.stalled(now)) {
                if (i != primary) primary = i
                return w
            }
            i = (i + 1) % workers.size
        }
        return workers[primary]
    }

    private fun wake(w: Worker) {
        if (w.state.get() == PARKED && w.state.compareAndSet(PARKED, NOTIFIED)) {
            wakes.increment()
            LockSupport.unpark(w)
        }
    }

    override fun endPass(now: Long): Long {
        if (passResumes.isNotEmpty()) {
            var from = 0
            var i = 0
            while (from < passResumes.size) {
                var w = workers[i % workers.size]
                var tries = 0
                while (w.stalled(now) && tries < workers.size) {
                    i++
                    tries++
                    w = workers[i % workers.size]
                }
                val to = minOf(from + BATCH_CHUNK, passResumes.size)
                for (k in from until to) w.inbox.add(passResumes[k])
                wake(w)
                from = to
                i++
            }
            passResumes.clear()
        }
        var recheck = NEVER
        for (w in workers) {
            val began = w.busySince
            if (began == 0L) continue
            val waits = !w.inbox.isEmpty() || !w.outbox.isEmpty()
            if (!waits) continue
            if (now - began > STALL_NANOS) {
                // Stuck in a resume: its finished writes go out now, and another thread takes what waits behind it.
                w.flushTask.run()
                if (!w.inbox.isEmpty()) workers.firstOrNull { it !== w && it.state.get() == PARKED }?.let { wake(it) }
            } else if (began + STALL_NANOS < recheck) {
                recheck = began + STALL_NANOS
            }
        }
        return recheck
    }

    override fun threads(): List<Thread> = workers.toList()

    override fun close() {
        running = false
        workers.forEach { LockSupport.unpark(it) }
    }

    private inner class Worker(
        private val index: Int,
    ) : BatchingThread("slice-handler-${if (batched) "b" else "a"}${index + 1}") {
        val inbox = ConcurrentLinkedQueue<Runnable>()
        val outbox = ConcurrentLinkedQueue<Runnable>()
        val state = AtomicInteger(RUNNING)

        /** When the resume this thread is in began; 0 between resumes. */
        @Volatile var busySince = 0L

        /** Loop thread: runs the writes this thread's handlers have handed over so far. */
        val flushTask =
            Runnable {
                while (true) {
                    val op = outbox.poll() ?: break
                    op.run()
                }
            }

        init {
            isDaemon = true
        }

        fun stalled(now: Long): Boolean {
            val began = busySince
            return began != 0L && now - began > STALL_NANOS
        }

        override fun batch(op: Runnable) {
            if (batched) outbox.add(op) else loop.execute(op)
        }

        private fun steal(): Runnable? {
            for (o in workers) {
                if (o === this) continue
                val began = o.busySince
                if (began != 0L && System.nanoTime() - began > STALL_NANOS) {
                    val t = o.inbox.poll()
                    if (t != null) {
                        steals.increment()
                        return t
                    }
                }
            }
            return null
        }

        override fun run() {
            var idleSince = 0L
            while (running) {
                val task = inbox.poll() ?: steal()
                if (task != null) {
                    busySince = System.nanoTime()
                    try {
                        task.run()
                    } finally {
                        busySince = 0L
                        outstanding.decrementAndGet()
                    }
                    idleSince = 0L
                    continue
                }
                if (!outbox.isEmpty()) loop.execute(flushTask)
                if (!batched) {
                    // The loop's own handler thread stays awake while the loop is at work or was a moment ago.
                    val now = System.nanoTime()
                    if (idleSince == 0L) idleSince = now
                    if (index == primary && ((SPIN_WHILE_LOOP_AWAKE && !loop.asleep) || now - idleSince < spinNanos)) {
                        Thread.onSpinWait()
                        continue
                    }
                }
                state.set(PARKED)
                if (inbox.isEmpty() && steal().also { if (it != null) inbox.add(it) } == null) {
                    while (state.get() == PARKED && running) LockSupport.park(this)
                }
                state.set(RUNNING)
                idleSince = 0L
            }
        }
    }
}

/**
 * The monitor of the on-loop design: every half millisecond while a loop is awake it looks at the unit of
 * user code each loop is in, and hands a loop whose unit has run past [budgetNanos] to a successor thread.
 * It parks when every loop is asleep and is woken by the first loop that wakes.
 */
internal class Watchdog(
    private val loops: List<SliceLoop>,
    private val budgetNanos: Long,
) : Thread("slice-watchdog") {
    @Volatile private var running = true

    @Volatile private var parked = false
    val ticks = LongAdder()

    init {
        isDaemon = true
        for (l in loops) l.onWake = { if (parked) LockSupport.unpark(this) }
    }

    override fun run() {
        var quiet = 0
        while (running) {
            LockSupport.parkNanos(this, 500_000L)
            ticks.increment()
            val now = System.nanoTime()
            var anyAwake = false
            for (l in loops) {
                l.checkOverrun(now, budgetNanos)
                if (!l.asleep) anyAwake = true
            }
            quiet = if (anyAwake) 0 else quiet + 1
            if (quiet >= 4) {
                parked = true
                if (loops.all { it.asleep }) LockSupport.park(this)
                parked = false
                quiet = 0
            }
        }
    }

    fun close() {
        running = false
        LockSupport.unpark(this)
    }
}

/**
 * A slice server on 127.0.0.1 with [serverLoops] loop threads (a UDP socket each, as a Netty QUIC server has
 * one event loop per socket) and [connectionCount] client connections spread over them; the clients share
 * [clientLoops] loops.
 *
 * placement: `loop` (handler on the server's loop), `watch` (on the loop, with the watchdog), `owned` (hand-off
 * pool), `ownedq` (queue pool), `ownedw` (hand-off pool, writes handed over), `batched`, `affine`, `adaptive`
 * (starts on the batched threads, moves to the loop while its resumes stay short, watchdog on).
 * callerRuns: client threads enter their connection to write; with `owned`/`ownedq` the handler threads do too.
 */
internal class SliceLoopback(
    private val placementName: String,
    holdAck: Boolean,
    callerRuns: Boolean,
    connectionCount: Int = 1,
    clientLoops: Int = 1,
    serverLoops: Int = 1,
) : AutoCloseable {
    private val api = loadQuicheApi()
    val probe: HandoffProbe? = if (System.getProperty("slice.probe") == "true") HandoffProbe() else null
    private val handlerThreads = ArrayList<HandlerThreads>()
    private val servers: List<SliceLoop>
    private val clients: List<SliceLoop>
    private val watchdog: Watchdog?
    val connections: List<SliceConn>
    val connection: SliceConn get() = connections[0]

    init {
        val cores = Runtime.getRuntime().availableProcessors()
        val kept = (cores / 2).coerceIn(2, 8)
        val watch = placementName == "watch" || placementName == "adaptive"
        val serverSettings = SliceSettings(holdAck, callerRuns && (placementName == "owned" || placementName == "ownedq"), watch)
        val handingOver = placementName == "ownedw" || placementName == "batched" || placementName == "affine" || placementName == "adaptive"
        val serverConfig = sliceConfig(api, server = true)
        val addresses = ArrayList<InetSocketAddress>()
        servers =
            List(serverLoops) { i ->
                val threads: HandlerThreads? =
                    when (placementName) {
                        "loop", "watch" -> null
                        "owned", "ownedw" -> HandOffThreads(probe)
                        "ownedq" -> QueueThreads(kept, probe)
                        "batched", "adaptive" -> LoopHandlers(batched = true, size = kept, spinNanos = 0)
                        "affine" -> LoopHandlers(batched = false, size = 3, spinNanos = AFFINE_SPIN_NANOS)
                        else -> error("unknown placement $placementName")
                    }
                if (threads != null) handlerThreads.add(threads)
                val placement =
                    when {
                        threads == null -> HandlerPlacement.OnLoop
                        placementName == "adaptive" -> HandlerPlacement.Adaptive(threads.dispatcher, threads.hooks)
                        else -> HandlerPlacement.Owned(threads.dispatcher, threads.hooks)
                    }
                val handler: suspend (SliceStream) -> Unit = if (handingOver) ::serveStreamHandingOver else ::serveStream
                val channel = openUdp()
                addresses.add(channel.localAddress as InetSocketAddress)
                SliceLoop("slice-server-loop${i + 1}", api, serverConfig, serverSettings, placement, handler).also {
                    it.start()
                    it.serve(channel)
                }
            }
        watchdog = if (watch) Watchdog(servers, WATCH_BUDGET_NANOS).also { it.start() } else null
        val clientSettings = SliceSettings(holdAck, callerRuns)
        val clientConfig = sliceConfig(api, server = false)
        clients =
            List(clientLoops) { i ->
                SliceLoop("slice-client-loop${i + 1}", api, clientConfig, clientSettings, HandlerPlacement.OnLoop) {}.also { it.start() }
            }
        connections = List(connectionCount) { i -> clients[i % clientLoops].connect(addresses[i % serverLoops]) }
    }

    fun serverThreads(): List<Thread> =
        servers.flatMap { it.threads() } + handlerThreads.flatMap { it.threads() } + listOfNotNull(watchdog)

    fun clientLoopThreads(): List<Thread> = clients.flatMap { it.threads() }

    /** Per-operation counts over everything since the loops started, both endpoints together. */
    fun report(ops: Long): String {
        val o = ops.coerceAtLeast(1).toDouble()

        fun per(v: Long) = "%.2f".format(v / o)

        fun server(f: (SliceCounts) -> LongAdder) = servers.sumOf { f(it.counts).sum() }

        fun client(f: (SliceCounts) -> LongAdder) = clients.sumOf { f(it.counts).sum() }

        fun both(f: (SliceCounts) -> LongAdder) = server(f) + client(f)
        val handlerWakes = handlerThreads.sumOf { it.wakes.sum() }
        val wakeUps = server { it.blockingSelects } + handlerWakes + client { it.blockingSelects } + client { it.resumesAcrossThreads }
        val text =
            "# slice counts per op over $ops ops: datagrams ${per(both { it.datagramsSent })}, " +
                "conn_send ${per(both { it.connSendCalls })}, conn_recv ${per(both { it.connRecvCalls })}, " +
                "WAKE-UPS ${per(wakeUps)} = server loop ${per(server { it.blockingSelects })} + handler threads ${per(handlerWakes)} + " +
                "client loops ${per(client { it.blockingSelects })} + callers ${per(client { it.resumesAcrossThreads })}, " +
                "resumes handed to handler threads ${per(server { it.resumesAcrossThreads })}, " +
                "loop tasks server ${per(server { it.tasks })} client ${per(client { it.tasks })}, " +
                "entered by caller server ${per(server { it.enteredByCaller })} client ${per(client { it.enteredByCaller })}, " +
                "left for the thread inside ${per(both { it.handedToOwner })}, " +
                "held flushes ${per(both { it.heldFlushes })}, send drops ${both { it.sendDrops }}, " +
                "send errors ${both { it.sendErrors }}, violations ${both { it.violations }}, " +
                "path mismatches ${both { it.pathMismatches }}, unroutable ${server { it.unroutable }}, paced ${both { it.paced }}, " +
                "loop takeovers ${server { it.takeovers }}, promotions ${server { it.promotions }}, demotions ${server { it.demotions }}, " +
                "stolen resumes ${handlerThreads.filterIsInstance<LoopHandlers>().sumOf { it.steals.sum() }}" +
                (if (watchdog == null) "" else ", watchdog ticks ${watchdog.ticks.sum()}")
        return if (probe == null) text else text + "\n" + probe.report()
    }

    fun violations(): Long = servers.sumOf { it.counts.violations.sum() } + clients.sumOf { it.counts.violations.sum() }

    fun takeovers(): Long = servers.sumOf { it.counts.takeovers.sum() }

    override fun close() {
        watchdog?.close()
        clients.forEach { it.close() }
        servers.forEach { it.close() }
        handlerThreads.forEach { it.close() }
    }

    private companion object {
        const val WATCH_BUDGET_NANOS = 1_000_000L
        val AFFINE_SPIN_NANOS = (System.getProperty("slice.affineSpinMicros") ?: "50").toLong() * 1000
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
        val r =
            when (val result = read()) {
                is NativeBuf -> result
                is SliceData -> result.buffer
                SliceEnd -> return got
            }
        got += r.remaining
        release(r)
    }
    return got
}

internal suspend fun SliceStream.readToEnd(): Int {
    var got = 0
    while (true) {
        val r =
            when (val result = read()) {
                is NativeBuf -> result
                is SliceData -> result.buffer
                SliceEnd -> return got
            }
        got += r.remaining
        release(r)
    }
}
