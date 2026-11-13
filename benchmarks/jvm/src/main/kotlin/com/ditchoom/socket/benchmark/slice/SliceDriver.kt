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
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/*
 * A benchmark-only slice of a run-to-completion QUIC driver, to measure the ceiling of that design. NOT product
 * code: one path, no migration, no 0-RTT, no datagrams, no connection-id rotation, no Retry or version
 * negotiation, connections routed by peer address, no bound on a waiting operation.
 *
 * A loop thread selects, reads datagrams, feeds quiche and completes waiting stream operations by plain method
 * calls, then flushes once per pass. Nothing inside is a coroutine: no channel, no command object awaiting a
 * deferred, no launch, no withTimeout. A stream operation suspends only when it must wait, once, and is resumed
 * directly.
 *
 * The one invariant everything rests on: AT MOST ONE THREAD IS INSIDE A CONNECTION AT A TIME. A thread enters a
 * connection by winning a compare-and-set on its owner; work that arrives while another thread is inside is left
 * in the connection's queue, and the thread inside runs it before it leaves (and looks again after leaving, so
 * nothing is stranded). With callerRuns off only the loop ever enters, so the compare-and-set always succeeds;
 * with it on, an application thread that writes enters the connection itself, calls quiche and sends the
 * datagram, and the loop is not woken.
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

/** Load-proof counts. */
internal class SliceCounts {
    val datagramsSent = LongAdder()
    val datagramsReceived = LongAdder()
    val connSendCalls = LongAdder()
    val connRecvCalls = LongAdder()
    val blockingSelects = LongAdder()
    val tasks = LongAdder()
    val resumesAcrossThreads = LongAdder()
    val heldFlushes = LongAdder()
    val sendDrops = LongAdder()
    val handedToOwner = LongAdder()
    val enteredByCaller = LongAdder()
    val violations = LongAdder()
    val sendErrors = LongAdder()
}

internal class SliceSettings(
    val holdAck: Boolean,
    val callerRuns: Boolean,
) {
    /** Counts entries into a connection and fails loudly on two at once. For the stress test only. */
    val check: Boolean = System.getProperty("slice.check") == "true"

    /** Mutation for the stress test: entering never waits for the thread inside. The test must fail with it. */
    val broken: Boolean = System.getProperty("slice.breakOwnership") == "true"
}

private const val AWAKE = 0
private const val ASLEEP = 1
private const val WOKEN = 2

private const val MAX_DATAGRAM = 1350
private const val READ_CHUNK = 64 * 1024
private const val QUICHE_DONE = -1
private const val SCID_LEN = 16
private const val HOLD_NANOS = 1_000_000L
private const val NEVER = Long.MAX_VALUE

private val NOBODY = Thread { }

internal fun openUdp(): DatagramChannel {
    val ch = DatagramChannel.open(StandardProtocolFamily.INET)
    ch.configureBlocking(false)
    ch.setOption(StandardSocketOptions.SO_RCVBUF, 2 * 1024 * 1024)
    ch.setOption(StandardSocketOptions.SO_SNDBUF, 2 * 1024 * 1024)
    ch.bind(InetSocketAddress("127.0.0.1", 0))
    return ch
}

private class TimerEntry(
    val deadline: Long,
    val conn: SliceConn,
)

private class HeldEntry(
    val since: Long,
    val conn: SliceConn,
)

/**
 * The loop. It is also the dispatcher of whatever runs on it: a resume from the loop thread runs in place,
 * a resume from another thread is queued and the selector woken (once, however many are queued).
 */
internal class SliceLoop(
    name: String,
    val api: QuicheApi,
    private val config: QuicheConfig,
    val settings: SliceSettings,
    private val placement: HandlerPlacement,
    private val handler: suspend (SliceStream) -> Unit,
) : CoroutineDispatcher() {
    val counts = SliceCounts()
    private val selector: Selector = Selector.open()
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val state = AtomicInteger(AWAKE)

    @Volatile private var running = true
    val thread = Thread(::run, name).also { it.isDaemon = true }
    private val handlerScope = CoroutineScope(SupervisorJob())

    /** Server side: connections by the peer's address. */
    private val byPeer = HashMap<SocketAddress, SliceConn>()
    private val all = ArrayList<SliceConn>()

    /** The connections this pass entered; each is finished and left at the end of the pass. */
    private val entered = ArrayList<SliceConn>()
    private val timers = PriorityQueue<TimerEntry>(64) { a, b -> a.deadline.compareTo(b.deadline) }
    private val held = ArrayDeque<HeldEntry>()
    private val recvBuf = NativeBuf(2048)
    private val random = SecureRandom()
    private val readPool = ConcurrentLinkedQueue<NativeBuf>()
    private val datagramPool = ConcurrentLinkedQueue<NativeBuf>()

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

    /** Server side: serves [channel]. */
    fun serve(channel: DatagramChannel) {
        val local = sockaddr(channel.localAddress as InetSocketAddress)
        execute { channel.register(selector, SelectionKey.OP_READ, ServerSocket(channel, local)) }
    }

    /** Client side: opens a connection to [peer] on a socket of its own and waits for the handshake. */
    fun connect(peer: InetSocketAddress): SliceConn {
        val channel = openUdp()
        channel.connect(peer)
        val done = AtomicReference<SliceConn>()
        val waiter = Thread.currentThread()
        execute {
            val name = NativeBuf(16)
            "localhost".forEachIndexed { i, ch -> name.bb.put(i, ch.code.toByte()) }
            val scid = NativeBuf(SCID_LEN).also { fillRandom(it, SCID_LEN) }
            val peerAddr = sockaddr(peer)
            val localAddr = sockaddr(channel.localAddress as InetSocketAddress)
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
            val c = SliceConn(this, conn, channel, peer, connected = true, peerAddr, localAddr, firstLocalStream = 0L)
            c.onEstablished = {
                done.set(c)
                LockSupport.unpark(waiter)
            }
            all.add(c)
            channel.register(selector, SelectionKey.OP_READ, c)
            enter(c)
            c.touched = true
        }
        val deadline = System.nanoTime() + 20_000_000_000L
        while (done.get() == null) {
            check(System.nanoTime() < deadline) { "handshake timed out" }
            LockSupport.parkNanos(50_000_000L)
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

    /** Loop thread: enters [c] for the rest of this pass. False when another thread is inside it. */
    fun enter(c: SliceConn): Boolean {
        if (c.ownedByMe()) return true
        if (!c.tryOwn()) return false
        entered.add(c)
        return true
    }

    private fun run() {
        while (running) {
            runQueued()
            // Work that ran just now has not been flushed yet: the loop may only sleep with nothing owed.
            if (queue.isEmpty() && entered.isEmpty()) {
                val waitNanos = nextWakeNanos()
                if (waitNanos > 0) {
                    state.set(ASLEEP)
                    if (queue.isEmpty()) {
                        counts.blockingSelects.increment()
                        // The selector's resolution is a millisecond; a shorter wait rounds up, as the library's does.
                        selector.select((waitNanos + 999_999L) / 1_000_000L)
                    }
                    state.set(AWAKE)
                } else {
                    selector.selectNow()
                }
            } else {
                selector.selectNow()
            }
            runQueued()
            val keys = selector.selectedKeys()
            if (!keys.isEmpty()) {
                for (key in keys) {
                    when (val a = key.attachment()) {
                        is SliceConn -> readConnected(a)
                        is ServerSocket -> readServer(a)
                    }
                }
                keys.clear()
            }
            expireTimers()
            // One pass: everything the datagrams and the queued work made ready is served, then one flush each.
            var i = 0
            while (i < entered.size) {
                entered[i].leave()
                i++
            }
            entered.clear()
        }
        for (c in all) {
            if (c.tryOwn()) {
                api.connClose(c.conn, QuicError.NoError)
                flush(c)
            }
            runCatching { c.channel.close() }
        }
        selector.close()
    }

    private fun runQueued() {
        while (true) {
            val task = queue.poll() ?: return
            counts.tasks.increment()
            task.run()
        }
    }

    private fun nextWakeNanos(): Long {
        val now = System.nanoTime()
        var wake = now + 1_000_000_000L
        val t = timers.peek()
        if (t != null && t.deadline < wake) wake = t.deadline
        val h = held.peek()
        if (h != null && h.since + HOLD_NANOS < wake) wake = h.since + HOLD_NANOS
        return wake - now
    }

    /** Whoever is inside [c], after a flush: when quiche next wants its timer run. */
    fun rearm(c: SliceConn) {
        val t = api.connTimeout(c.conn)
        val at = if (t == null) NEVER else System.nanoTime() + t.inWholeNanoseconds
        c.timeoutAt = at
        // A later deadline costs nothing: the entry already queued fires early and re-queues itself.
        if (at < c.queuedDeadline) {
            if (isLoopThread) pushTimer(c, at) else execute(c.armTask)
        }
    }

    fun pushTimer(
        c: SliceConn,
        at: Long,
    ) {
        c.queuedDeadline = at
        timers.add(TimerEntry(at, c))
    }

    fun noteHeld(c: SliceConn) {
        held.add(HeldEntry(c.heldSince, c))
    }

    private fun expireTimers() {
        val now = System.nanoTime()
        while (true) {
            val e = timers.peek() ?: break
            if (e.deadline > now) break
            timers.poll()
            val c = e.conn
            if (e.deadline != c.queuedDeadline) continue
            c.queuedDeadline = NEVER
            val at = c.timeoutAt
            if (at <= now) {
                c.timeoutAt = NEVER
                if (enter(c)) c.onTimeout() else c.handOff(c.timeoutOp)
            } else if (at != NEVER) {
                pushTimer(c, at)
            }
        }
        while (true) {
            val h = held.peek() ?: break
            val c = h.conn
            if (c.heldSince != h.since) {
                held.poll()
                continue
            }
            if (h.since + HOLD_NANOS > now) break
            held.poll()
            if (enter(c)) c.holdExpired() else c.handOff(c.holdExpiredOp)
        }
    }

    private fun readConnected(c: SliceConn) {
        var n = 0
        while (n < 64) {
            val bb = recvBuf.bb
            bb.clear()
            val got =
                try {
                    c.channel.read(bb)
                } catch (_: java.io.IOException) {
                    0
                }
            if (got <= 0) return
            feed(c, bb.position())
            n++
        }
    }

    private fun readServer(s: ServerSocket) {
        var n = 0
        while (n < 64) {
            val bb = recvBuf.bb
            bb.clear()
            val from = s.channel.receive(bb) ?: return
            val c = byPeer[from] ?: accept(s, from as InetSocketAddress)
            feed(c, bb.position())
            n++
        }
    }

    private fun feed(
        c: SliceConn,
        len: Int,
    ) {
        counts.datagramsReceived.increment()
        if (enter(c)) {
            c.receive(recvBuf.address, len)
            return
        }
        // Another thread is inside the connection: it takes the datagram before it leaves.
        val copy = datagramPool.poll() ?: NativeBuf(2048)
        copy.bb.clear()
        recvBuf.bb.flip()
        copy.bb.put(recvBuf.bb)
        copy.limit = len
        c.handOff(
            Runnable {
                c.receive(copy.address, copy.limit)
                datagramPool.add(copy)
            },
        )
    }

    private fun accept(
        s: ServerSocket,
        peer: InetSocketAddress,
    ): SliceConn {
        val scid = NativeBuf(SCID_LEN).also { fillRandom(it, SCID_LEN) }
        val peerAddr = sockaddr(peer)
        val conn =
            api.accept(scid.address, SCID_LEN, 0L, 0, s.local.address, s.local.limit, peerAddr.address, peerAddr.limit, config)
        check(conn.handle != 0L) { "quiche_accept failed" }
        val c = SliceConn(this, conn, s.channel, peer, connected = false, peerAddr, s.local, firstLocalStream = 1L)
        byPeer[peer] = c
        all.add(c)
        return c
    }

    /** Whoever is inside [c]: sends everything quiche has, then re-arms its timer. */
    fun flush(c: SliceConn) {
        c.heldSince = 0L
        val out = c.sendBuf
        while (true) {
            counts.connSendCalls.increment()
            val n = api.connSend(c.conn, out.address, MAX_DATAGRAM, c.sendInfo)
            if (n <= 0) break
            val bb = out.bb
            bb.clear()
            bb.limit(n)
            val sent =
                try {
                    if (c.connected) c.channel.write(bb) else c.channel.send(bb, c.peer)
                } catch (_: java.io.IOException) {
                    0
                }
            if (sent == 0) counts.sendDrops.increment() else counts.datagramsSent.increment()
        }
        rearm(c)
    }

    fun startHandler(stream: SliceStream) {
        when (placement) {
            HandlerPlacement.OnLoop ->
                // A stream a handler thread found while it was inside the connection is still served on the loop.
                if (isLoopThread) {
                    handlerScope.launch(this, CoroutineStart.UNDISPATCHED) { handler(stream) }
                } else {
                    handlerScope.launch(this) { handler(stream) }
                }
            is HandlerPlacement.Owned -> handlerScope.launch(placement.dispatcher) { handler(stream) }
        }
    }

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

    private class ServerSocket(
        val channel: DatagramChannel,
        val local: NativeBuf,
    )

    private companion object {
        val IS_BSD = System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it || "bsd" in it }
    }
}

/** A write that quiche has not taken all of yet. */
internal class PendingWrite(
    val stream: SliceStream,
    val src: NativeBuf,
    val fin: Boolean,
    val continuation: CancellableContinuation<Unit>,
)

/**
 * One connection. Every field but the owner, the queue and the two deadlines belongs to whichever thread is
 * inside the connection.
 */
internal class SliceConn(
    val loop: SliceLoop,
    val conn: QuicheConn,
    val channel: DatagramChannel,
    val peer: SocketAddress,
    val connected: Boolean,
    peerAddr: NativeBuf,
    localAddr: NativeBuf,
    firstLocalStream: Long,
) {
    private val api = loop.api
    val callerRuns: Boolean = loop.settings.callerRuns
    private val check = loop.settings.check
    val recvInfo: QuicheRecvInfo = api.recvInfoNew(peerAddr.address, peerAddr.limit, localAddr.address, localAddr.limit)
    val sendInfo: QuicheSendInfo = api.sendInfoNew()
    val sendBuf = NativeBuf(MAX_DATAGRAM)

    private val owner = AtomicReference(NOBODY)
    private val waiting = ConcurrentLinkedQueue<Runnable>()
    private val inside = AtomicInteger()

    private val streams = HashMap<Long, SliceStream>()
    private val pendingWrites = ArrayList<PendingWrite>()
    private val nextLocalStream = AtomicLong(firstLocalStream)
    private val servesPeerStreams = firstLocalStream == 1L
    private val ids = LongArray(256)
    private var establishedSeen = false
    var onEstablished: () -> Unit = {}

    /** Something changed since the last flush. */
    var touched = false

    @Volatile var timeoutAt = NEVER

    /** The deadline of this connection's live entry in the loop's timer queue. */
    @Volatile var queuedDeadline = NEVER

    /** When the flush of a pass was held for the application's reply; 0 when none is held. */
    @Volatile var heldSince = 0L

    private var received = 0
    private var deliveredToWaiter = 0
    private var otherWork = 0
    private var heldLast = false
    private var misses = 0
    private var sitOut = 0

    val armTask = Runnable { if (timeoutAt < queuedDeadline) loop.pushTimer(this, timeoutAt) }
    val timeoutOp = Runnable { onTimeout() }
    val holdExpiredOp = Runnable { holdExpired() }

    fun ownedByMe(): Boolean = owner.get() === Thread.currentThread()

    fun tryOwn(): Boolean {
        if (loop.settings.broken) {
            owner.set(Thread.currentThread())
        } else if (!owner.compareAndSet(NOBODY, Thread.currentThread())) {
            return false
        }
        if (check && inside.incrementAndGet() != 1) violation()
        return true
    }

    private fun violation() {
        loop.counts.violations.increment()
        System.err.println("VIOLATION: two threads inside one connection; " + Thread.currentThread().name)
    }

    /** Asserts the caller is the one thread inside. Called before every quiche call when checking. */
    private fun guard() {
        if (check && (inside.get() != 1 || owner.get() !== Thread.currentThread())) violation()
    }

    /**
     * The thread inside: runs what arrived meanwhile, finishes the pass (serve, then flush or hold), leaves,
     * and looks once more so that work queued during the last steps is not stranded.
     */
    fun leave() {
        runWaiting()
        finishPass()
        if (check) inside.decrementAndGet()
        owner.set(NOBODY)
        drain()
    }

    /** Leaves [op] for whoever is inside; runs it here if nobody is. */
    fun handOff(op: Runnable) {
        loop.counts.handedToOwner.increment()
        waiting.add(op)
        drain()
    }

    private fun drain() {
        while (!waiting.isEmpty()) {
            if (!tryOwn()) return
            runWaiting()
            finishPass()
            if (check) inside.decrementAndGet()
            owner.set(NOBODY)
        }
    }

    private fun runWaiting() {
        while (true) {
            val op = waiting.poll() ?: return
            op.run()
        }
    }

    private fun finishPass() {
        if (!touched) return
        serve()
        touched = false
        if (tryHold()) {
            loop.rearm(this)
            loop.noteHeld(this)
        } else {
            guard()
            loop.flush(this)
        }
    }

    fun receive(
        address: Long,
        len: Int,
    ) {
        guard()
        loop.counts.connRecvCalls.increment()
        api.connRecv(conn, address, len, recvInfo)
        received++
        touched = true
    }

    fun onTimeout() {
        guard()
        api.connOnTimeout(conn)
        touched = true
    }

    fun holdExpired() {
        if (heldSince == 0L) return
        if (++misses >= 2) {
            misses = 0
            sitOut = 256
        }
        touched = true
    }

    fun openStream(): SliceStream = SliceStream(this, nextLocalStream.getAndAdd(4))

    fun work() {
        otherWork++
        touched = true
    }

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

    fun recv(
        id: Long,
        buf: NativeBuf,
    ): StreamRecvResult {
        guard()
        return api.connStreamRecv(conn, QuicStreamId(id), buf.address, buf.capacity)
    }

    fun send(
        id: Long,
        address: Long,
        len: Int,
        fin: Boolean,
    ): Int {
        guard()
        return api.connStreamSend(conn, QuicStreamId(id), address, len, fin).result
    }

    private fun serve() {
        guard()
        if (!establishedSeen && api.connIsEstablished(conn)) {
            establishedSeen = true
            onEstablished()
        }
        if (received > 0) {
            val it = api.connReadable(conn)
            var n = 0
            while (n < ids.size) {
                val id = api.streamIterNext(it) ?: break
                ids[n++] = id.id
            }
            api.streamIterFree(it)
            for (i in 0 until n) {
                val id = ids[i]
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
                w.stream.resumeWriter(w.continuation)
            } else {
                i++
            }
        }
    }

    /**
     * Holds this pass's flush for the reply when the pass only handed one datagram's bytes to a reader that
     * was waiting (the ACK would otherwise leave alone). Never twice in a row, and only on the loop thread,
     * which is the one that can end the hold on its deadline.
     */
    private fun tryHold(): Boolean {
        val wasHeld = heldSince != 0L
        var hold =
            loop.settings.holdAck && loop.isLoopThread && establishedSeen && !wasHeld && !heldLast &&
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
            loop.counts.heldFlushes.increment()
        }
        return hold
    }
}

/**
 * One bidirectional stream. The suspend edge of the slice: an operation that can finish at once returns
 * without suspending; one that must wait suspends once and is resumed by whichever thread completes it.
 */
internal class SliceStream(
    private val conn: SliceConn,
    val id: Long,
) {
    private val loop = conn.loop
    var adopted = false

    /** The reader waiting for bytes. Set by the reader's thread, taken by the thread inside the connection. */
    private val waiter = AtomicReference<CancellableContinuation<SliceRead>?>(null)

    /** Bytes are waiting in quiche and no reader was there to take them. */
    @Volatile private var readable = false

    @Volatile private var recvFinished = false
    private var sendFinished = false

    private val deliverOp = Runnable { deliver() }
    private val deliverTask =
        Runnable {
            if (loop.enter(conn)) deliver() else conn.handOff(deliverOp)
        }

    fun markReadable() {
        readable = true
    }

    suspend fun read(): SliceRead {
        if (recvFinished) return SliceEnd
        if (conn.ownedByMe()) {
            val now = recvNow()
            if (now != null) return now
            return suspendCancellableCoroutine { c ->
                waiter.set(c)
                c.invokeOnCancellation { waiter.compareAndSet(c, null) }
            }
        }
        if (conn.callerRuns && readable && conn.tryOwn()) {
            // Bytes that arrived before this reader did: it takes them itself.
            loop.counts.enteredByCaller.increment()
            val now = recvNow()
            conn.leave()
            if (now != null) return now
        }
        return suspendCancellableCoroutine { c ->
            waiter.set(c)
            c.invokeOnCancellation { waiter.compareAndSet(c, null) }
            // Otherwise whoever feeds the connection finds this waiter when bytes arrive, and nobody is woken now.
            if (readable || recvFinished) {
                if (conn.callerRuns) conn.handOff(deliverOp) else loop.execute(deliverTask)
            }
        }
    }

    fun release(buf: NativeBuf) = loop.release(buf)

    /** Inside the connection. Hands the waiting reader what quiche has; true if a reader was resumed. */
    fun deliver(): Boolean {
        readable = true
        val c = waiter.getAndSet(null) ?: return false
        val now = if (recvFinished) SliceEnd else recvNow()
        if (now == null) {
            waiter.set(c)
            return false
        }
        if (c.context[ContinuationInterceptor] !== loop || !loop.isLoopThread) loop.counts.resumesAcrossThreads.increment()
        c.resume(now)
        return true
    }

    /** Inside the connection. Null when quiche has nothing for this stream. */
    private fun recvNow(): SliceRead? {
        val buf = loop.takeReadBuf()
        when (val r = conn.recv(id, buf)) {
            is StreamRecvResult.Data -> {
                if (r.fin) finishRecv()
                conn.touched = true
                if (r.bytesRead == 0 && r.fin) {
                    loop.release(buf)
                    return SliceEnd
                }
                buf.position = 0
                buf.limit = r.bytesRead
                // quiche gives all it has up to the buffer's size, so a short read left nothing behind.
                if (r.bytesRead < buf.capacity) readable = false
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
        if (conn.ownedByMe()) {
            conn.adopt(this)
            conn.work()
            if (sendSome(src, fin)) return
            return suspendCancellableCoroutine { c -> conn.addPending(PendingWrite(this, src, fin, c)) }
        }
        if (conn.callerRuns) {
            if (conn.tryOwn()) {
                // This thread is inside the connection: it calls quiche and sends the datagram itself.
                loop.counts.enteredByCaller.increment()
                conn.adopt(this)
                conn.work()
                if (sendSome(src, fin)) {
                    conn.leave()
                    return
                }
                return suspendCancellableCoroutine { c ->
                    conn.addPending(PendingWrite(this, src, fin, c))
                    conn.leave()
                }
            }
            return suspendCancellableCoroutine { c -> conn.handOff { writeInside(src, fin, c) } }
        }
        return suspendCancellableCoroutine { c ->
            loop.execute {
                if (loop.enter(conn)) writeInside(src, fin, c) else conn.handOff { writeInside(src, fin, c) }
            }
        }
    }

    private fun writeInside(
        src: NativeBuf,
        fin: Boolean,
        c: CancellableContinuation<Unit>,
    ) {
        conn.adopt(this)
        conn.work()
        if (sendSome(src, fin)) resumeWriter(c) else conn.addPending(PendingWrite(this, src, fin, c))
    }

    suspend fun shutdownSend() = write(EMPTY, fin = true)

    fun resumeWriter(c: CancellableContinuation<Unit>) {
        if (c.context[ContinuationInterceptor] !== loop || !loop.isLoopThread) loop.counts.resumesAcrossThreads.increment()
        c.resume(Unit)
    }

    /** Inside the connection. True when all of [src] (and the FIN, if asked) is with quiche. */
    fun sendSome(
        src: NativeBuf,
        fin: Boolean,
    ): Boolean {
        while (true) {
            val left = src.remaining
            val n = conn.send(id, src.address + src.position, left, fin)
            if (n == QUICHE_DONE) return false
            if (n < 0) {
                // The stream or the connection is gone; the reader learns of it.
                loop.counts.sendErrors.increment()
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
