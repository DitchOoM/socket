package com.ditchoom.socket.benchmark.slice

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StreamRecvResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/*
 * A benchmark-only slice of a run-to-completion QUIC driver, to measure the ceiling of that design. NOT product
 * code: one path, no migration, no 0-RTT, no datagrams, no connection-id rotation, no Retry or version
 * negotiation, connections routed by peer address.
 *
 * One loop thread owns its connections. It selects, reads datagrams, feeds quiche and completes waiting stream
 * operations by plain method calls, then flushes once per pass. Nothing inside is a coroutine: no channel, no
 * command object awaiting a deferred, no launch, no withTimeout. A stream operation suspends only when it must
 * wait, once, and the loop resumes it directly.
 */

/** A direct buffer and its native address. Readable bytes are [position, limit). */
internal class NativeBuf(
    val capacity: Int,
) : SliceRead {
    val bb: ByteBuffer = ByteBuffer.allocateDirect(capacity)
    val address: Long = ADDRESS.getLong(bb)

    var position = 0
    var limit = 0

    val remaining: Int get() = limit - position

    private companion object {
        // java.nio.Buffer.address; the benchmark JVM already runs with --add-opens java.base/java.nio.
        val ADDRESS =
            java.nio.Buffer::class.java
                .getDeclaredField("address")
                .also { it.isAccessible = true }
    }
}

/** What a read came to: bytes (a [NativeBuf] to hand back through [SliceStream.release]) or the peer's FIN. */
internal sealed interface SliceRead

internal data object SliceEnd : SliceRead

/** Where a server runs the coroutine that serves a stream. */
internal sealed interface HandlerPlacement {
    /** On the loop thread, inside the read pass. */
    data object OnLoop : HandlerPlacement

    /** On [dispatcher], threads the server owns. */
    class Owned(
        val dispatcher: CoroutineDispatcher,
    ) : HandlerPlacement
}

/** Load-proof counts, written by the loop thread only and read after it has stopped or between iterations. */
internal class SliceCounts {
    @Volatile var datagramsSent = 0L

    @Volatile var datagramsReceived = 0L

    @Volatile var connSendCalls = 0L

    @Volatile var connRecvCalls = 0L

    @Volatile var blockingSelects = 0L

    @Volatile var tasks = 0L

    @Volatile var resumesOffLoop = 0L

    @Volatile var heldFlushes = 0L

    @Volatile var sendDrops = 0L
}

private const val AWAKE = 0
private const val ASLEEP = 1
private const val WOKEN = 2

private const val MAX_DATAGRAM = 1350
private const val READ_CHUNK = 64 * 1024
private const val QUICHE_DONE = -1
private const val SCID_LEN = 16
private const val HOLD_NANOS = 1_000_000L

/**
 * The loop. It is also the dispatcher of whatever runs on it: a resume from the loop thread runs in place,
 * a resume from another thread is queued and the selector woken (once, however many are queued).
 */
internal class SliceLoop(
    name: String,
    private val api: QuicheApi,
    private val config: QuicheConfig,
    private val channel: DatagramChannel,
    private val serverSide: Boolean,
    private val placement: HandlerPlacement,
    private val holdAck: Boolean,
    private val handler: suspend (SliceStream) -> Unit,
) : CoroutineDispatcher() {
    val counts = SliceCounts()
    private val selector: Selector = Selector.open()
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val state = AtomicInteger(AWAKE)

    @Volatile private var running = true
    private val thread = Thread(::run, name).also { it.isDaemon = true }
    private val handlerScope = CoroutineScope(SupervisorJob())

    private val connections = HashMap<SocketAddress, SliceConn>()
    private val dirty = ArrayList<SliceConn>()
    private val recvBuf = NativeBuf(2048)
    private val sendBuf = NativeBuf(MAX_DATAGRAM)
    private val localAddr = sockaddr(channel.localAddress as InetSocketAddress)
    private val random = SecureRandom()
    private val readPool = ConcurrentLinkedQueue<NativeBuf>()
    private val ids = LongArray(256)

    val isLoopThread: Boolean get() = Thread.currentThread() === thread

    fun start() = thread.start()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isLoopThread

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = execute(block)

    fun execute(task: Runnable) {
        queue.add(task)
        if (!isLoopThread && state.compareAndSet(ASLEEP, WOKEN)) selector.wakeup()
    }

    fun takeReadBuf(): NativeBuf = readPool.poll() ?: NativeBuf(READ_CHUNK)

    fun release(buf: NativeBuf) {
        readPool.add(buf)
    }

    /** Client side: the one connection, opened on the loop. */
    fun connect(peer: InetSocketAddress): SliceConn {
        val done = AtomicReference<SliceConn>()
        val waiter = Thread.currentThread()
        execute {
            val name = NativeBuf(16)
            "localhost".forEachIndexed { i, ch -> name.bb.put(i, ch.code.toByte()) }
            val scid = NativeBuf(SCID_LEN).also { fillRandom(it, SCID_LEN) }
            val peerAddr = sockaddr(peer)
            val conn =
                api.connect(
                    name.address,
                    9,
                    scid.address,
                    SCID_LEN,
                    localAddr.address,
                    localAddr.limit,
                    peerAddr.address,
                    peerAddr.limit,
                    config,
                )
            check(conn.handle != 0L) { "quiche_connect failed" }
            val c = SliceConn(this, api, conn, peer, peerAddr, localAddr, firstLocalStream = 0L)
            c.onEstablished = {
                done.set(c)
                LockSupport.unpark(waiter)
            }
            connections[peer] = c
            markDirty(c)
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (done.get() == null) {
            check(System.nanoTime() < deadline) { "handshake timed out" }
            LockSupport.parkNanos(100_000_000L)
        }
        return done.get()
    }

    fun close() {
        running = false
        selector.wakeup()
        thread.join(5_000)
        handlerScope.cancel()
    }

    private fun fillRandom(
        buf: NativeBuf,
        n: Int,
    ) {
        repeat(n) { buf.bb.put(it, random.nextInt().toByte()) }
    }

    fun markDirty(conn: SliceConn) {
        if (!conn.dirty) {
            conn.dirty = true
            dirty.add(conn)
        }
    }

    private fun run() {
        val key = channel.register(selector, SelectionKey.OP_READ)
        while (running) {
            runQueued()
            // Work that ran just now has not been flushed yet: the loop may only sleep with nothing owed.
            if (queue.isEmpty() && dirty.isEmpty()) {
                val waitNanos = nextWakeNanos()
                if (waitNanos > 0) {
                    state.set(ASLEEP)
                    if (queue.isEmpty()) {
                        counts.blockingSelects++
                        // The selector's resolution is a millisecond; a shorter wait rounds up, as the library's does.
                        val t0 = System.nanoTime()
                        selector.select((waitNanos + 999_999L) / 1_000_000L)
                        if (DEBUG && System.nanoTime() - t0 > 900_000_000L) {
                            System.err.println("[${thread.name}] slept a full tick; queued=${queue.size} state=${state.get()}")
                            dump()
                        }
                    }
                    state.set(AWAKE)
                } else {
                    selector.selectNow()
                }
            } else {
                selector.selectNow()
            }
            val readable = selector.selectedKeys().remove(key)
            runQueued()
            if (readable) readDatagrams()
            expireTimers()
            // One pass: everything the datagrams and the queued work made ready is served, then one flush.
            var i = 0
            while (i < dirty.size) {
                dirty[i].serve()
                i++
            }
            i = 0
            while (i < dirty.size) {
                val c = dirty[i]
                c.dirty = false
                if (c.tryHold()) rearm(c) else flush(c)
                i++
            }
            dirty.clear()
        }
        for (c in connections.values) {
            api.connClose(c.conn, QuicError.NoError)
            flush(c)
            c.free(api)
        }
        channel.close()
        selector.close()
    }

    private fun dump() {
        for (c in connections.values) {
            val n = readable(c)
            val r = (0 until n).joinToString(",") { idAt(it).toString() }
            System.err.println("[${thread.name}] idle tick: quiche readable=[$r] ${c.describe()}")
        }
        if (connections.values.any { it.hasStreams() }) {
            for ((t, st) in Thread.getAllStackTraces()) {
                if (t === thread || st.isEmpty()) continue
                if (!(t.name.startsWith("slice-") || t.name.contains("jmh") || t.name.contains("Default") || t.name.contains("Bench"))) continue
                System.err.println("  thread ${t.name} ${t.state}: " + st.take(7).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber })
            }
        }
    }

    private fun runQueued() {
        while (true) {
            val task = queue.poll() ?: return
            counts.tasks++
            task.run()
        }
    }

    private fun nextWakeNanos(): Long {
        val now = System.nanoTime()
        // A second at most, so a bound on a waiting operation is checked without a timer of its own.
        var wake = now + 1_000_000_000L
        for (c in connections.values) {
            if (c.timeoutAt < wake) wake = c.timeoutAt
            if (c.heldSince != 0L && c.heldSince + HOLD_NANOS < wake) wake = c.heldSince + HOLD_NANOS
        }
        return wake - now
    }

    private fun expireTimers() {
        val now = System.nanoTime()
        for (c in connections.values) {
            if (now >= c.timeoutAt) {
                api.connOnTimeout(c.conn)
                markDirty(c)
            }
            if (c.heldSince != 0L && now >= c.heldSince + HOLD_NANOS) {
                c.holdMissed()
                markDirty(c)
            }
        }
    }

    private fun readDatagrams() {
        var n = 0
        while (n < 64) {
            val bb = recvBuf.bb
            bb.clear()
            val from: SocketAddress
            if (serverSide) {
                from = channel.receive(bb) ?: return
            } else {
                if (channel.read(bb) <= 0) return
                from = channel.remoteAddress
            }
            counts.datagramsReceived++
            val conn = connections[from] ?: if (serverSide) accept(from as InetSocketAddress) else return
            counts.connRecvCalls++
            api.connRecv(conn.conn, recvBuf.address, bb.position(), conn.recvInfo)
            conn.received++
            markDirty(conn)
            n++
        }
    }

    private fun accept(peer: InetSocketAddress): SliceConn {
        val scid = NativeBuf(SCID_LEN).also { fillRandom(it, SCID_LEN) }
        val peerAddr = sockaddr(peer)
        val conn =
            api.accept(scid.address, SCID_LEN, 0L, 0, localAddr.address, localAddr.limit, peerAddr.address, peerAddr.limit, config)
        check(conn.handle != 0L) { "quiche_accept failed" }
        val c = SliceConn(this, api, conn, peer, peerAddr, localAddr, firstLocalStream = 1L)
        connections[peer] = c
        return c
    }

    fun flush(c: SliceConn) {
        c.heldSince = 0L
        while (true) {
            counts.connSendCalls++
            val n = api.connSend(c.conn, sendBuf.address, MAX_DATAGRAM, c.sendInfo)
            if (n <= 0) break
            val bb = sendBuf.bb
            bb.clear()
            bb.limit(n)
            val sent =
                try {
                    if (serverSide) channel.send(bb, c.peer) else channel.write(bb)
                } catch (_: java.io.IOException) {
                    0
                }
            if (sent == 0) counts.sendDrops++ else counts.datagramsSent++
        }
        rearm(c)
    }

    fun rearm(c: SliceConn) {
        val t = api.connTimeout(c.conn)
        c.timeoutAt = if (t == null) Long.MAX_VALUE else System.nanoTime() + t.inWholeNanoseconds
    }

    fun startHandler(stream: SliceStream) {
        when (placement) {
            HandlerPlacement.OnLoop -> handlerScope.launch(this, CoroutineStart.UNDISPATCHED) { handler(stream) }
            is HandlerPlacement.Owned -> handlerScope.launch(placement.dispatcher) { handler(stream) }
        }
    }

    val holds: Boolean get() = holdAck

    fun recv(
        c: SliceConn,
        id: Long,
        buf: NativeBuf,
    ): StreamRecvResult = api.connStreamRecv(c.conn, QuicStreamId(id), buf.address, buf.capacity)

    fun send(
        c: SliceConn,
        id: Long,
        address: Long,
        len: Int,
        fin: Boolean,
    ): Int = api.connStreamSend(c.conn, QuicStreamId(id), address, len, fin).result

    fun established(c: SliceConn): Boolean = api.connIsEstablished(c.conn)

    /** Fills [ids] with the readable streams of [c]; returns how many. */
    fun readable(c: SliceConn): Int {
        val it = api.connReadable(c.conn)
        var n = 0
        while (n < ids.size) {
            val id = api.streamIterNext(it) ?: break
            ids[n++] = id.id
        }
        api.streamIterFree(it)
        return n
    }

    fun idAt(i: Int): Long = ids[i]

    private fun sockaddr(a: InetSocketAddress): NativeBuf {
        val ip = (a.address as Inet4Address).address
        val buf = NativeBuf(16)
        val bb = buf.bb
        if (IS_BSD) {
            bb.put(0, 16)
            bb.put(1, 2)
        } else {
            bb.put(0, 2)
            bb.put(1, 0)
        }
        bb.put(2, (a.port shr 8).toByte())
        bb.put(3, a.port.toByte())
        for (i in 0 until 4) bb.put(4 + i, ip[i])
        buf.limit = 16
        return buf
    }

    companion object {
        val IS_BSD = System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it || "bsd" in it }
        val DEBUG = System.getProperty("slice.debug") == "true"
    }
}

/** A write that quiche has not taken all of yet. */
internal class PendingWrite(
    val stream: SliceStream,
    val src: NativeBuf,
    val fin: Boolean,
    val continuation: CancellableContinuation<Unit>,
)

/** One connection; every field belongs to the loop thread. */
internal class SliceConn(
    val loop: SliceLoop,
    api: QuicheApi,
    val conn: QuicheConn,
    val peer: SocketAddress,
    peerAddr: NativeBuf,
    localAddr: NativeBuf,
    firstLocalStream: Long,
) {
    val recvInfo: QuicheRecvInfo
    val sendInfo: QuicheSendInfo
    private val streams = HashMap<Long, SliceStream>()
    private val pendingWrites = ArrayList<PendingWrite>()
    private val nextLocalStream = AtomicLong(firstLocalStream)
    private val servesPeerStreams = firstLocalStream == 1L
    private var establishedSeen = false
    var onEstablished: () -> Unit = {}

    var dirty = false
    var timeoutAt = Long.MAX_VALUE

    /** When the flush of a pass was held for the application's reply; 0 when none is held. */
    var heldSince = 0L

    /** What the current pass did, for the hold's decision. */
    var received = 0
    private var deliveredToWaiter = 0
    private var otherWork = 0
    private var heldLast = false

    /** Misses in a row (a held flush that left on its deadline, no reply came), and eligible passes to sit out. */
    private var misses = 0
    private var sitOut = 0

    fun holdMissed() {
        if (++misses >= 2) {
            misses = 0
            sitOut = 256
        }
    }

    init {
        recvInfo = api.recvInfoNew(peerAddr.address, peerAddr.limit, localAddr.address, localAddr.limit)
        sendInfo = api.sendInfoNew()
    }

    fun free(api: QuicheApi) {
        api.recvInfoFree(recvInfo)
        api.sendInfoFree(sendInfo)
        api.connFree(conn)
    }

    fun hasStreams(): Boolean = streams.isNotEmpty()

    fun describe(): String =
        "streams=" + streams.values.joinToString(";") { it.describe() } + " pendingWrites=${pendingWrites.size} held=${heldSince != 0L}"

    fun openStream(): SliceStream {
        val id = nextLocalStream.getAndAdd(4)
        return SliceStream(this, id)
    }

    fun noteWork() {
        otherWork++
        loop.markDirty(this)
    }

    /** Loop thread: registers a stream the application opened, on its first use. */
    fun adopt(stream: SliceStream) {
        if (!stream.adopted) {
            stream.adopted = true
            streams[stream.id] = stream
        }
    }

    fun forget(stream: SliceStream) {
        streams.remove(stream.id)
    }

    fun addPending(w: PendingWrite) {
        pendingWrites.add(w)
    }

    /** Loop thread, once per pass: serves what the pass made ready. */
    fun serve() {
        if (!establishedSeen && loop.established(this)) {
            establishedSeen = true
            onEstablished()
        }
        if (received > 0) {
            val n = loop.readable(this)
            // Copied out first: a handler resumed in place may open, read or finish streams.
            val batch = LongArray(n) { loop.idAt(it) }
            for (id in batch) {
                val known = streams[id]
                if (known != null) {
                    if (known.deliver()) deliveredToWaiter++ else otherWork++
                } else if (servesPeerStreams && (id and 3L) == 0L) {
                    val s = SliceStream(this, id)
                    adopt(s)
                    s.markReadable()
                    otherWork++
                    loop.startHandler(s)
                }
            }
            if (pendingWrites.isNotEmpty()) retryWrites()
        }
    }

    private fun retryWrites() {
        var i = 0
        while (i < pendingWrites.size) {
            val w = pendingWrites[i]
            if (w.stream.sendSome(w.src, w.fin)) {
                pendingWrites.removeAt(i)
                w.stream.resumeFromLoop(w.continuation)
            } else {
                i++
            }
        }
    }

    /**
     * Holds this pass's flush for the reply when the pass only handed one datagram's bytes to a reader that
     * was waiting off the loop (the ACK would otherwise leave alone). Never twice in a row.
     */
    fun tryHold(): Boolean {
        val wasHeld = heldSince != 0L
        var hold =
            loop.holds && establishedSeen && !wasHeld && !heldLast &&
                received == 1 && deliveredToWaiter > 0 && otherWork == 0
        if (hold && sitOut > 0) {
            sitOut--
            hold = false
        }
        // A held flush that leaves because the application wrote is a hit.
        if (wasHeld && otherWork > 0) misses = 0
        received = 0
        deliveredToWaiter = 0
        otherWork = 0
        heldLast = hold
        if (hold) {
            heldSince = System.nanoTime()
            loop.counts.heldFlushes++
        }
        return hold
    }
}

/**
 * One bidirectional stream. The suspend edge of the slice: an operation that can finish at once returns
 * without suspending; one that must wait suspends once and the loop resumes it.
 */
internal class SliceStream(
    private val conn: SliceConn,
    val id: Long,
) {
    private val loop = conn.loop
    var adopted = false

    /** The reader waiting for bytes. Set by the reader's thread, taken by the loop. */
    private val waiter = AtomicReference<CancellableContinuation<SliceRead>?>(null)

    /** Bytes are waiting in quiche and no reader was there to take them. */
    @Volatile private var readable = false

    @Volatile private var recvFinished = false
    private var sendFinished = false

    private val deliverTask =
        Runnable {
            t { "task" }
            deliver()
        }

    fun describe(): String =
        "$id(waiter=${waiter.get() != null},readable=$readable,recvFin=$recvFinished,sendFin=$sendFinished) trace=" + trace.joinToString(" | ")

    private val trace = ConcurrentLinkedQueue<String>()

    private inline fun t(what: () -> String) {
        if (SliceLoop.DEBUG) trace.add(Thread.currentThread().name.takeLast(8) + ":" + what())
    }

    fun markReadable() {
        readable = true
    }

    suspend fun read(): SliceRead {
        if (recvFinished) return SliceEnd
        if (loop.isLoopThread) {
            val now = recvNow()
            if (now != null) return now
            return suspendCancellableCoroutine { c ->
                waiter.set(c)
                c.invokeOnCancellation { waiter.compareAndSet(c, null) }
            }
        }
        return suspendCancellableCoroutine { c ->
            waiter.set(c)
            t { "read-set readable=$readable" }
            c.invokeOnCancellation { waiter.compareAndSet(c, null) }
            // Bytes that arrived before this reader did: ask the loop for them. Otherwise the loop finds the
            // waiter when they arrive and is not woken now.
            if (readable || recvFinished) {
                t { "read-exec" }
                loop.execute(deliverTask)
                t { "read-exec-done" }
            } else {
                t { "read-noexec" }
            }
        }
    }

    fun release(buf: NativeBuf) = loop.release(buf)

    /** Loop thread. Hands the waiting reader what quiche has; true if a reader was resumed. */
    fun deliver(): Boolean {
        readable = true
        val c = waiter.getAndSet(null)
        t { "deliver waiter=${c != null}" }
        if (c == null) return false
        val now = if (recvFinished) SliceEnd else recvNow()
        t { "deliver got=" + (if (now == null) "none" else if (now === SliceEnd) "end" else "data") }
        if (now == null) {
            waiter.set(c)
            return false
        }
        if (loop.isDispatchNeededFor(c)) loop.counts.resumesOffLoop++
        c.resume(now)
        return true
    }

    /** Loop thread. Null when quiche has nothing for this stream. */
    private fun recvNow(): SliceRead? {
        val buf = loop.takeReadBuf()
        when (val r = loop.recv(conn, id, buf)) {
            is StreamRecvResult.Data -> {
                if (r.fin) finishRecv()
                if (r.bytesRead == 0 && r.fin) {
                    loop.release(buf)
                    return SliceEnd
                }
                buf.position = 0
                buf.limit = r.bytesRead
                // quiche gives all it has up to the buffer's size, so a short read left nothing behind.
                if (r.bytesRead < buf.capacity) readable = false
                conn.loop.markDirty(conn)
                return buf
            }
            StreamRecvResult.Done -> {
                loop.release(buf)
                readable = false
                return null
            }
            is StreamRecvResult.Error, is StreamRecvResult.Reset, StreamRecvResult.ConnectionGone -> {
                loop.release(buf)
                finishRecv()
                return SliceEnd
            }
        }
    }

    private fun finishRecv() {
        recvFinished = true
        if (sendFinished) conn.forget(this)
    }

    /** Writes [src] from its position to its limit; returns once quiche has taken all of it. */
    suspend fun write(
        src: NativeBuf,
        fin: Boolean = false,
    ) {
        if (loop.isLoopThread) {
            conn.adopt(this)
            conn.noteWork()
            if (sendSome(src, fin)) return
            return suspendCancellableCoroutine { c -> conn.addPending(PendingWrite(this, src, fin, c)) }
        }
        return suspendCancellableCoroutine { c ->
            loop.execute {
                t { "write-task fin=$fin" }
                conn.adopt(this)
                conn.noteWork()
                if (sendSome(src, fin)) resumeFromLoop(c) else conn.addPending(PendingWrite(this, src, fin, c))
            }
        }
    }

    suspend fun shutdownSend() = write(EMPTY, fin = true)

    fun resumeFromLoop(c: CancellableContinuation<Unit>) {
        if (loop.isDispatchNeededFor(c)) loop.counts.resumesOffLoop++
        c.resume(Unit)
    }

    /** Loop thread. True when all of [src] (and the FIN, if asked) is with quiche. */
    fun sendSome(
        src: NativeBuf,
        fin: Boolean,
    ): Boolean {
        while (true) {
            val left = src.remaining
            val n = loop.send(conn, id, src.address + src.position, left, fin)
            if (n == QUICHE_DONE) return false
            if (n < 0) {
                // The stream or the connection is gone; the reader learns of it.
                return true
            }
            src.position += n
            if (src.remaining == 0) {
                if (fin) {
                    sendFinished = true
                    if (recvFinished) conn.forget(this)
                }
                return true
            }
            if (n == 0) return false
        }
    }

    private companion object {
        val EMPTY = NativeBuf(1)
    }
}

/** Whether resuming [c] from the loop thread crosses to another thread. */
internal fun SliceLoop.isDispatchNeededFor(c: CancellableContinuation<*>): Boolean {
    val d = c.context[kotlin.coroutines.ContinuationInterceptor]
    return d !== this
}
