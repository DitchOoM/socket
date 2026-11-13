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
import kotlin.coroutines.AbstractCoroutineContextElement
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

/** The public API's shape: a result object over the buffer, made for each read. */
internal class SliceData(
    val buffer: NativeBuf,
) : SliceRead

/** A connection id as a map key: up to 20 bytes. */
internal class CidKey(
    var a: Long = 0,
    var b: Long = 0,
    var c: Int = 0,
    var len: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is CidKey && other.a == a && other.b == b && other.c == c && other.len == len

    override fun hashCode(): Int = (a xor (a ushr 32) xor b xor (b ushr 29)).toInt() * 31 + c

    fun copy(): CidKey = CidKey(a, b, c, len)
}

private const val ARMED = 0
private const val FIRED = 1
private const val DISARMED = 2
private const val COMPACT_MIN = 64
private const val OP_TIMEOUT_NANOS = 30_000_000_000L

/** One bounded wait. */
internal class DeadlineNode(
    val due: Long,
    val fire: Runnable,
) {
    val state = AtomicInteger(ARMED)
    var next: DeadlineNode? = null
}

/**
 * The deadlines of a loop's waiting operations, in the shape of the library's deadline queue: any thread arms
 * by pushing onto a lock-free inbox (the loop is not woken), the loop moves the inbox into a heap when it next
 * runs, a disarmed node stays in the heap until enough are dead to rebuild it.
 */
internal class Deadlines {
    private val inbox = AtomicReference<DeadlineNode?>(null)
    private val heap = PriorityQueue<DeadlineNode>(256) { x, y -> x.due.compareTo(y.due) }
    private val dead = AtomicInteger()

    fun arm(
        due: Long,
        fire: Runnable,
    ): DeadlineNode {
        val node = DeadlineNode(due, fire)
        while (true) {
            val head = inbox.get()
            node.next = head
            if (inbox.compareAndSet(head, node)) return node
        }
    }

    fun disarm(node: DeadlineNode) {
        if (node.state.compareAndSet(ARMED, DISARMED)) dead.incrementAndGet()
    }

    /** Loop thread. Fires what is due; returns the next deadline or [NEVER]. */
    fun fireDue(now: Long): Long {
        var chain = inbox.getAndSet(null)
        while (chain != null) {
            val following = chain.next
            chain.next = null
            heap.add(chain)
            chain = following
        }
        val d = dead.get()
        if (d >= COMPACT_MIN && d * 2 >= heap.size) {
            heap.removeIf { it.state.get() == DISARMED }
            dead.set(0)
        }
        while (true) {
            val top = heap.peek() ?: return NEVER
            if (top.due > now) return top.due
            heap.poll()
            if (top.state.compareAndSet(ARMED, FIRED)) top.fire.run() else dead.decrementAndGet()
        }
    }
}

/** Reads of native memory the quiche binding has no call for (the send time in quiche's send info). */
private object Raw {
    private val unsafe: sun.misc.Unsafe =
        sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null) as sun.misc.Unsafe

    fun long(address: Long): Long = unsafe.getLong(address)
}

/** Debug: how far ahead of now quiche's send times are, as counts by bucket. */
internal class PaceAhead {
    val past = LongAdder()
    val under50us = LongAdder()
    val under1ms = LongAdder()
    val under100ms = LongAdder()
    val beyond = LongAdder()

    @Volatile var firstAhead = Long.MIN_VALUE

    @Volatile var maxAhead = Long.MIN_VALUE

    fun record(aheadNanos: Long) {
        if (firstAhead == Long.MIN_VALUE) firstAhead = aheadNanos
        if (aheadNanos > maxAhead) maxAhead = aheadNanos
        when {
            aheadNanos <= 0 -> past
            aheadNanos < 50_000 -> under50us
            aheadNanos < 1_000_000 -> under1ms
            aheadNanos < 100_000_000 -> under100ms
            else -> beyond
        }.increment()
    }

    override fun toString(): String = "first ${firstAhead} ns, max ${maxAhead} ns, past/now ${past.sum()}, <50us ${under50us.sum()}, <1ms ${under1ms.sum()}, <100ms ${under100ms.sum()}, beyond ${beyond.sum()}"
}

internal val DEBUG_PACE: Boolean = System.getProperty("slice.debugPace") == "true"

/** quiche fills the send time of a datagram on Linux only; elsewhere the field is zero. */
internal val PACING_AVAILABLE: Boolean = System.getProperty("os.name").lowercase().contains("linux")

internal data object SliceEnd : SliceRead

/** Where a server runs the coroutine that serves a stream. */
internal sealed interface HandlerPlacement {
    /** On the loop thread, inside the read pass. */
    data object OnLoop : HandlerPlacement

    /**
     * On [dispatcher], threads the server owns. [hooks] lets the dispatcher see the loop's passes (to batch
     * what one pass hands over, or to keep a handler thread spinning while the loop is at work).
     */
    class Owned(
        val dispatcher: CoroutineDispatcher,
        val hooks: LoopHooks = NoHooks,
    ) : HandlerPlacement

    /**
     * A handler starts on [dispatcher]'s threads. Once its resumes have stayed short it is resumed on the
     * loop instead, and it goes back to the threads on its first long resume.
     */
    class Adaptive(
        val dispatcher: CoroutineDispatcher,
        val hooks: LoopHooks,
    ) : HandlerPlacement
}

/** A resume shorter than this counts toward running on the loop; a resume on the loop longer than this ends it. */
internal const val PROMOTE_BUDGET_NANOS = 20_000L
internal const val PROMOTE_AFTER = 16

/** What a set of handler threads may want from the loop that feeds it. All called on the loop thread. */
internal interface LoopHooks {
    fun attach(loop: SliceLoop) {}

    /** The pass is over. Returns when the loop should look again even if nothing else wakes it, or [NEVER]. */
    fun endPass(now: Long): Long = NEVER

    /** Resumes handed to the handler threads and not finished yet; the loop spins briefly instead of sleeping while any are. */
    fun outstanding(): Int = 0
}

internal object NoHooks : LoopHooks

internal const val NEVER = Long.MAX_VALUE

/** The connection a handler coroutine serves, so the loop knows which connection a unit of user code belongs to. */
internal class ConnElement(
    val conn: SliceConn,
) : AbstractCoroutineContextElement(Key) {
    /** Adaptive placement, decided per connection: its handlers' resumes run on the loop. */
    val onLoop: Boolean get() = conn.handlersOnLoop

    /** A resume off the loop took [nanos]. */
    fun ranOffLoop(
        nanos: Long,
        counts: SliceCounts,
    ) {
        if (nanos > PROMOTE_BUDGET_NANOS) {
            conn.shortResumes = 0
        } else if (++conn.shortResumes >= PROMOTE_AFTER && !conn.handlersOnLoop) {
            conn.handlersOnLoop = true
            counts.promotions.increment()
        }
    }

    /** A resume on the loop took [nanos]. */
    fun ranOnLoop(
        nanos: Long,
        counts: SliceCounts,
    ) {
        if (nanos > PROMOTE_BUDGET_NANOS && conn.handlersOnLoop) {
            conn.handlersOnLoop = false
            conn.shortResumes = 0
            counts.demotions.increment()
        }
    }

    companion object Key : CoroutineContext.Key<ConnElement>
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
    val takeovers = LongAdder()
    val pathMismatches = LongAdder()
    val paced = LongAdder()
    val unroutable = LongAdder()
    val closedSeen = LongAdder()

    /** Flush after handlers: a pass that received data and wrote its reply; a fallback hold; how each hold ended. */
    val repliedInPass = LongAdder()
    val fallbackHolds = LongAdder()
    val fallbackRides = LongAdder()
    val fallbackTimerFires = LongAdder()
    val paceAhead = PaceAhead()
    val promotions = LongAdder()
    val demotions = LongAdder()
    val sendErrors = LongAdder()
}

internal class SliceSettings(
    val holdAck: Boolean,
    val callerRuns: Boolean,
    /** A monitor may hand the loop to a successor thread when a unit of user code overruns. */
    val watch: Boolean = false,
) {
    /** Counts entries into a connection and fails loudly on two at once. For the stress test only. */
    val check: Boolean = System.getProperty("slice.check") == "true"

    /**
     * The per-request work a real driver does and the bare slice skips, each switchable (`-Dslice.real=` a comma
     * list of dl,dcid,rr,pace,chk, or `all`):
     * dl   every operation that waits arms a deadline and disarms it when it completes;
     * dcid the server parses each datagram's QUIC header and finds the connection by destination connection id;
     * rr   a read returns a result object over a buffer the caller releases, as the public API does;
     * pace a datagram quiche stamps with a later send time is held until then (quiche gives a time on Linux only);
     * chk  each datagram's source and destination are checked against the connection's path, and each pass
     *      asks quiche whether the connection closed and whether a path event is waiting.
     */
    private val real: Set<String> =
        (System.getProperty("slice.real") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val deadlines: Boolean = "all" in real || "dl" in real
    val dcid: Boolean = "all" in real || "dcid" in real
    val result: Boolean = "all" in real || "rr" in real
    val pacing: Boolean = ("all" in real || "pace" in real) && PACING_AVAILABLE
    val checks: Boolean = "all" in real || "chk" in real

    /**
     * Flush after handlers (`-Dslice.fah=true`, with the hold on): a pass serves every connection it read for,
     * hands over what goes to handler threads, runs the writes that have arrived meanwhile, and only then
     * flushes. A connection that wrote in the pass flushes at once, its ACK and its reply together, with no
     * timer; one that received data and wrote nothing falls back to the short hold.
     */
    val fah: Boolean = System.getProperty("slice.fah") == "true"

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
private const val PACE_SLACK_NANOS = 50_000L
private const val DEPOSED = -1L
private val DEBUG_TAKEOVER = System.getProperty("slice.debugTakeover") == "true"
private const val QUIC_TYPE_INITIAL = 1
private val LOOP_SPIN_NANOS = (System.getProperty("slice.loopSpinMicros") ?: "100").toLong() * 1000

private val NOBODY = Thread { }

internal fun openUdp(bind: InetSocketAddress = InetSocketAddress("127.0.0.1", 0)): DatagramChannel {
    val ch = DatagramChannel.open(if (bind.address is java.net.Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
    ch.configureBlocking(false)
    ch.setOption(StandardSocketOptions.SO_RCVBUF, 2 * 1024 * 1024)
    ch.setOption(StandardSocketOptions.SO_SNDBUF, 2 * 1024 * 1024)
    ch.bind(bind)
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

    /** The thread that is the loop now. It changes only when the watchdog hands the loop to a successor. */
    @Volatile var thread: LoopRunner = LoopRunner("$name-1")
        private set
    private val runners = ConcurrentLinkedQueue<LoopRunner>().also { it.add(thread) }
    private val standby = ConcurrentLinkedQueue<LoopRunner>()
    private val runnerNames = AtomicInteger(1)
    private val baseName = name

    /**
     * 0 when the loop is not in user code; else the time the unit of user code began; [DEPOSED] once the
     * watchdog has taken the loop away from the thread that is still in that unit.
     */
    val unit = AtomicLong(0)

    @Volatile private var takeoverFrom: LoopRunner = thread
    private var passRecheckAt = NEVER
    private val hooks: LoopHooks =
        when (placement) {
            HandlerPlacement.OnLoop -> NoHooks
            is HandlerPlacement.Owned -> placement.hooks
            is HandlerPlacement.Adaptive -> placement.hooks
        }

    /** The dispatcher of an adaptive handler: the loop for a promoted handler, the handler threads otherwise. */
    private val adaptive: CoroutineDispatcher =
        object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = !(isLoopThread && context[ConnElement]?.onLoop == true)

            override fun dispatch(
                context: CoroutineContext,
                block: Runnable,
            ) {
                val e = context[ConnElement]
                val threads = (placement as HandlerPlacement.Adaptive).dispatcher
                if (e == null) {
                    threads.dispatch(context, block)
                } else if (e.onLoop) {
                    execute(UserTask(block, e.conn, e))
                } else {
                    threads.dispatch(
                        context,
                        Runnable {
                            val began = System.nanoTime()
                            block.run()
                            e.ranOffLoop(System.nanoTime() - began, counts)
                        },
                    )
                }
            }
        }

    /** The handler element when resuming [context] here and now runs user code in place on the loop thread. */
    fun runsInPlace(context: CoroutineContext): Boolean {
        if (!isLoopThread) return false
        val interceptor = context[ContinuationInterceptor]
        return interceptor === this || (interceptor === adaptive && context[ConnElement]?.onLoop == true)
    }
    private val handlerScope = CoroutineScope(SupervisorJob())

    /** Set by the watchdog: called when the loop wakes from a blocking select. */
    @Volatile var onWake: () -> Unit = {}

    val asleep: Boolean get() = state.get() == ASLEEP

    fun threads(): List<Thread> = runners.toList()

    /** A thread that can be the loop. One is; the others wait to succeed it. */
    inner class LoopRunner(
        name: String,
    ) : Thread(name) {
        @Volatile private var go = false

        /** This thread was the loop and lost it while in user code; it may only finish its own connection. */
        var deposed = false

        /** The connection whose handler this thread is running, when it is in a unit of user code. */
        var unitConn: SliceConn? = null

        init {
            isDaemon = true
        }

        fun assign() {
            go = true
            LockSupport.unpark(this)
        }

        override fun run() {
            while (running) {
                while (!go && running) LockSupport.park(this)
                if (!running) return
                go = false
                deposed = false
                loopBody(this)
                unitConn = null
                standby.add(this)
            }
        }
    }

    /**
     * Runs [block], user code that belongs to [conn], as one unit the watchdog can time. If the watchdog took
     * the loop away meanwhile, this thread is no longer the loop when [block] returns.
     */
    inline fun userCode(
        conn: SliceConn?,
        element: ConnElement?,
        block: () -> Unit,
    ) {
        val me = thread
        if (!settings.watch || Thread.currentThread() !== me || unit.get() != 0L) {
            block()
            return
        }
        me.unitConn = conn
        val began = System.nanoTime()
        unit.set(began)
        block()
        element?.ranOnLoop(System.nanoTime() - began, counts)
        if (!unit.compareAndSet(began, 0L)) {
            me.deposed = true
            // The watchdog names the successor right after it deposes; until then this thread must not look like the loop.
            while (thread === me) Thread.onSpinWait()
        }
    }

    /** The watchdog: hands the loop to a successor when the unit of user code in progress has run past [budget]. */
    fun checkOverrun(
        now: Long,
        budget: Long,
    ) {
        val began = unit.get()
        if (began <= 0L || now - began <= budget) return
        if (!unit.compareAndSet(began, DEPOSED)) return
        val old = thread
        if (DEBUG_TAKEOVER) {
            System.err.println("[takeover] ${old.name} in a unit for ${(now - began) / 1000} us: " +
                old.stackTrace.take(14).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber })
        }
        val successor = standby.poll() ?: LoopRunner("$baseName-${runnerNames.incrementAndGet()}").also {
            runners.add(it)
            it.start()
        }
        takeoverFrom = old
        thread = successor
        successor.assign()
    }

    /** The successor: takes every connection the deposed thread had entered, except the one its handler is in. */
    private fun adopt(me: LoopRunner) {
        val old = takeoverFrom
        val busy = old.unitConn
        val mine = ArrayList<SliceConn>()
        for (c in entered) if (c !== busy && c.transfer(old, me)) mine.add(c)
        entered.clear()
        entered.addAll(mine)
        unit.set(0L)
        state.set(AWAKE)
        counts.takeovers.increment()
    }

    /** Server side: connections by the peer's address. */
    private val byPeer = HashMap<SocketAddress, SliceConn>()
    private val all = ArrayList<SliceConn>()

    /** The connections this pass entered; each is finished and left at the end of the pass. */
    private val entered = ArrayList<SliceConn>()
    private val timers = PriorityQueue<TimerEntry>(64) { a, b -> a.deadline.compareTo(b.deadline) }
    private val held = ArrayDeque<HeldEntry>()
    private val recvBuf = NativeBuf(2048)
    val deadlines = Deadlines()
    private var nextDeadline = NEVER

    /** Server side, when routing by connection id: connections by every id that reaches them. */
    private val byCid = HashMap<CidKey, SliceConn>()
    private val probe = CidKey()
    private val header = if (settings.dcid) HeaderScratch() else null
    private val random = SecureRandom()
    private val readPool = ConcurrentLinkedQueue<NativeBuf>()
    private val datagramPool = ConcurrentLinkedQueue<NativeBuf>()

    val isLoopThread: Boolean get() = Thread.currentThread() === thread

    fun start() {
        hooks.attach(this)
        thread.start()
        thread.assign()
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isLoopThread

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = execute(if (settings.watch) UserTask(block, context[ConnElement]?.conn, null) else block)

    /** A resume of user code queued for the loop, and the connection it belongs to. */
    private class UserTask(
        val block: Runnable,
        val conn: SliceConn?,
        val element: ConnElement?,
    ) : Runnable {
        override fun run() = block.run()
    }

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
    fun connect(
        peer: InetSocketAddress,
        local: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    ): SliceConn {
        val channel = openUdp(local)
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
        for (r in runners) LockSupport.unpark(r)
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

    private fun loopBody(me: LoopRunner) {
        if (takeoverFrom !== me && unit.get() == DEPOSED) adopt(me)
        while (running) {
            runQueued()
            if (me.deposed) return finishDeposed(me)
            // Work that ran just now has not been flushed yet: the loop may only sleep with nothing owed.
            if (queue.isEmpty() && entered.isEmpty()) {
                if (hooks.outstanding() > 0) {
                    // A handler thread is at work on what this pass handed it: its write is a moment away.
                    val spinFrom = System.nanoTime()
                    while (queue.isEmpty() && hooks.outstanding() > 0 && System.nanoTime() - spinFrom < LOOP_SPIN_NANOS) Thread.onSpinWait()
                }
                val waitNanos = nextWakeNanos()
                if (!queue.isEmpty()) {
                    selector.selectNow()
                } else if (waitNanos > 0) {
                    state.set(ASLEEP)
                    if (queue.isEmpty()) {
                        counts.blockingSelects.increment()
                        // The selector's resolution is a millisecond; a shorter wait rounds up, as the library's does.
                        selector.select((waitNanos + 999_999L) / 1_000_000L)
                    }
                    state.set(AWAKE)
                    onWake()
                } else {
                    selector.selectNow()
                }
            } else {
                selector.selectNow()
            }
            runQueued()
            if (me.deposed) return finishDeposed(me)
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
            if (settings.fah) {
                // Serve first, everywhere; then whatever writes that made ready; and only then flush.
                var k = 0
                while (k < entered.size) {
                    entered[k].servePhase()
                    if (me.deposed) return finishDeposed(me)
                    k++
                }
                passRecheckAt = hooks.endPass(System.nanoTime())
                runQueued()
                if (me.deposed) return finishDeposed(me)
            }
            // One pass: everything the datagrams and the queued work made ready is served, then one flush each.
            var i = 0
            while (i < entered.size) {
                entered[i].leave()
                // User code runs inside leave(): if it overran, another thread is the loop now and owns this list.
                if (me.deposed) return
                i++
            }
            entered.clear()
            if (!settings.fah) passRecheckAt = hooks.endPass(System.nanoTime())
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

    /** A deposed thread: it leaves the one connection it may still be inside, and stops being a loop. */
    private fun finishDeposed(me: LoopRunner) {
        val c = me.unitConn
        if (c != null && c.ownedByMe()) c.leave()
    }

    private fun runQueued() {
        while (true) {
            val task = queue.poll() ?: return
            counts.tasks.increment()
            if (task is UserTask) {
                userCode(task.conn, task.element) { task.run() }
                if ((Thread.currentThread() as LoopRunner).deposed) return
            } else {
                task.run()
                if (settings.watch && (Thread.currentThread() as LoopRunner).deposed) return
            }
        }
    }

    /** Loop thread: [c] was entered outside the usual places and is to be finished with this pass. */
    fun noteEntered(c: SliceConn) {
        entered.add(c)
    }

    private fun nextWakeNanos(): Long {
        val now = System.nanoTime()
        var wake = now + 1_000_000_000L
        val t = timers.peek()
        if (t != null && t.deadline < wake) wake = t.deadline
        val h = held.peek()
        if (h != null && h.since + HOLD_NANOS < wake) wake = h.since + HOLD_NANOS
        if (passRecheckAt < wake) wake = passRecheckAt
        if (nextDeadline < wake) wake = nextDeadline
        return wake - now
    }

    /** Whoever is inside [c], after a flush: when quiche next wants its timer run. */
    fun rearm(c: SliceConn) {
        val t = api.connTimeout(c.conn)
        var at = if (t == null) NEVER else System.nanoTime() + t.inWholeNanoseconds
        if (c.pacedLen > 0 && c.pacedAt < at) at = c.pacedAt
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
        if (settings.deadlines) nextDeadline = deadlines.fireDue(now)
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
            val c =
                if (header == null) {
                    byPeer[from] ?: accept(s, from as InetSocketAddress)
                } else {
                    route(s, from as InetSocketAddress, bb.position())
                }
            if (c == null) {
                counts.unroutable.increment()
            } else {
                // A datagram from an address that is not the connection's path: a real driver starts a path here.
                if (settings.checks && from != c.peer) counts.pathMismatches.increment()
                feed(c, bb.position())
            }
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

    /** The connection a datagram is for, by the destination connection id in its header. */
    private fun route(
        s: ServerSocket,
        from: InetSocketAddress,
        len: Int,
    ): SliceConn? {
        val h = header!!
        h.reset()
        val rc =
            api.headerInfo(
                recvBuf.address,
                len,
                SCID_LEN,
                h.version.address,
                h.type.address,
                h.scid.address,
                h.scidLen.address,
                h.dcid.address,
                h.dcidLen.address,
                h.token.address,
                h.tokenLen.address,
            )
        if (rc < 0) return null
        h.key(probe)
        val known = byCid[probe]
        if (known != null) return known
        // Only a client's first packets carry an id this server did not issue.
        if (h.type.bb.get(0).toInt() != QUIC_TYPE_INITIAL) return null
        val c = accept(s, from)
        byCid[probe.copy()] = c
        return c
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
        if (settings.dcid) byCid[CidKey(scid.bb.getLong(0), scid.bb.getLong(8), 0, SCID_LEN)] = c
        all.add(c)
        return c
    }

    /** Whoever is inside [c]: sends everything quiche has, then re-arms its timer. */
    fun flush(c: SliceConn) {
        c.heldSince = 0L
        val out = c.sendBuf
        if (c.pacedLen > 0) {
            // A datagram is waiting for its send time: nothing overtakes it.
            if (System.nanoTime() < c.pacedAt) {
                rearm(c)
                return
            }
            transmit(c, c.pacedLen)
            c.pacedLen = 0
        }
        while (true) {
            counts.connSendCalls.increment()
            val n = api.connSend(c.conn, out.address, MAX_DATAGRAM, c.sendInfo)
            if (n <= 0) break
            if (settings.checks) c.checkDestination()
            if (settings.pacing) {
                val at = c.sendTime()
                if (DEBUG_PACE) counts.paceAhead.record(at - System.nanoTime())
                if (at - System.nanoTime() > PACE_SLACK_NANOS) {
                    c.pacedLen = n
                    c.pacedAt = at
                    counts.paced.increment()
                    break
                }
            }
            transmit(c, n)
        }
        rearm(c)
    }

    private fun transmit(
        c: SliceConn,
        n: Int,
    ) {
        val bb = c.sendBuf.bb
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

    fun startHandler(stream: SliceStream) {
        when (placement) {
            HandlerPlacement.OnLoop -> {
                val context = if (settings.watch) this + ConnElement(stream.conn) else this
                // A stream found by a thread that is not the loop is still served on the loop.
                if (isLoopThread) {
                    userCode(stream.conn, null) { handlerScope.launch(context, CoroutineStart.UNDISPATCHED) { handler(stream) } }
                } else {
                    handlerScope.launch(context) { handler(stream) }
                }
            }
            is HandlerPlacement.Owned -> handlerScope.launch(placement.dispatcher) { handler(stream) }
            is HandlerPlacement.Adaptive -> {
                val element = ConnElement(stream.conn)
                // A new stream of a connection that has earned the loop starts there, in place.
                userCode(stream.conn, if (isLoopThread && element.onLoop) element else null) {
                    handlerScope.launch(adaptive + element) { handler(stream) }
                }
            }
        }
    }

    private fun sockaddr(a: InetSocketAddress): NativeBuf {
        val address = a.address
        val ip = address.address
        val v6 = address is java.net.Inet6Address
        val size = if (v6) 28 else 16
        val buf = NativeBuf(size)
        val bb = buf.bb
        // sockaddr_in / sockaddr_in6: BSD leads with a length byte, Linux has a two-byte family.
        val family = if (!v6) 2 else if (IS_BSD) 30 else 10
        if (IS_BSD) {
            bb.put(0, size.toByte())
            bb.put(1, family.toByte())
        } else {
            bb.put(0, family.toByte())
            bb.put(1, 0)
        }
        bb.put(2, (a.port shr 8).toByte())
        bb.put(3, a.port.toByte())
        if (v6) {
            for (i in 0 until 16) bb.put(8 + i, ip[i])
            bb.order(java.nio.ByteOrder.nativeOrder()).putInt(24, (address as java.net.Inet6Address).scopeId)
        } else {
            for (i in 0 until 4) bb.put(4 + i, ip[i])
        }
        buf.limit = size
        return buf
    }

    private class ServerSocket(
        val channel: DatagramChannel,
        val local: NativeBuf,
    )

    /** The out-parameters of quiche_header_info. */
    private class HeaderScratch {
        val version = NativeBuf(8)
        val type = NativeBuf(8)
        val scid = NativeBuf(24)
        val scidLen = NativeBuf(8)
        val dcid = NativeBuf(24)
        val dcidLen = NativeBuf(8)
        val token = NativeBuf(256)
        val tokenLen = NativeBuf(8)

        init {
            for (b in listOf(scidLen, dcidLen, tokenLen)) b.bb.order(java.nio.ByteOrder.nativeOrder())
        }

        fun reset() {
            scidLen.bb.putLong(0, 20)
            dcidLen.bb.putLong(0, 20)
            tokenLen.bb.putLong(0, 256)
            dcid.bb.putLong(0, 0)
            dcid.bb.putLong(8, 0)
            dcid.bb.putInt(16, 0)
        }

        fun key(into: CidKey) {
            into.a = dcid.bb.getLong(0)
            into.b = dcid.bb.getLong(8)
            into.c = dcid.bb.getInt(16)
            into.len = dcidLen.bb.getLong(0).toInt()
        }
    }

    private companion object {
        val IS_BSD = System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it || "bsd" in it }
    }
}

/** A write that quiche has not taken all of yet. */
internal class PendingWrite(
    val stream: SliceStream,
    val src: NativeBuf,
    val fin: Boolean,
    /** The writer to resume; null when the write was handed over with its buffer and nobody waits. */
    val continuation: CancellableContinuation<Unit>?,
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

    /** A datagram held in [sendBuf] until its send time. */
    var pacedLen = 0
    var pacedAt = 0L

    private val toOffset = api.sendInfoToAddr(sendInfo) - sendInfo.handle
    private val expectedPort = (peer as InetSocketAddress).port
    private val pathScratch = if (loop.settings.checks) Array(4) { NativeBuf(if (it % 2 == 0) 128 else 8) } else emptyArray()

    /** quiche's send time of the datagram it just wrote, on the clock of System.nanoTime (Linux). */
    fun sendTime(): Long {
        val at = sendInfo.handle + toOffset + 128 + 8
        return Raw.long(at) * 1_000_000_000L + Raw.long(at + 8)
    }

    /** The datagram quiche just wrote is for this connection's path (a real driver sends to the address quiche names). */
    fun checkDestination() {
        val to = api.sendInfoToAddr(sendInfo)
        if (api.sockAddrPort(to) != expectedPort) loop.counts.pathMismatches.increment()
    }

    /** Once per pass: did the connection close, is a path event waiting. */
    private fun checkState() {
        if (api.connIsClosed(conn)) {
            loop.counts.closedSeen.increment()
            for (s in streams.values.toList()) s.connectionGone()
        }
        val event = api.connPathEventNext(conn, pathScratch[0].address, pathScratch[1].address, pathScratch[2].address, pathScratch[3].address)
        if (event != null) loop.counts.pathMismatches.increment()
    }

    /** Adaptive placement: this connection's handlers have kept their resumes short and run on the loop. */
    @Volatile var handlersOnLoop = false

    @Volatile var shortResumes = 0

    @Volatile var timeoutAt = NEVER

    /** The deadline of this connection's live entry in the loop's timer queue. */
    @Volatile var queuedDeadline = NEVER

    /** When the flush of a pass was held for the application's reply; 0 when none is held. */
    @Volatile var heldSince = 0L

    private var received = 0
    private var servedUpTo = 0
    private var wrote = 0
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
        if (loop.isLoopThread) {
            // The loop finishes connections at the end of its pass, never in the middle of reading datagrams.
            if (tryOwn()) loop.noteEntered(this)
        } else {
            drain()
        }
    }

    /** The loop's successor takes this connection from the deposed thread, which is not inside it. */
    fun transfer(
        from: Thread,
        to: Thread,
    ): Boolean = owner.compareAndSet(from, to)

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
        if (loop.settings.checks) checkState()
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
        // Not quiche's timer when the deadline was a paced datagram's send time; quiche then finds nothing due.
        api.connOnTimeout(conn)
        touched = true
    }

    fun holdExpired() {
        if (heldSince == 0L) return
        loop.counts.fallbackTimerFires.increment()
        if (++misses >= 2) {
            misses = 0
            sitOut = 256
        }
        touched = true
    }

    fun openStream(): SliceStream = SliceStream(this, nextLocalStream.getAndAdd(4))

    fun work() {
        otherWork++
        wrote++
        touched = true
    }

    /** Flush after handlers, loop thread: the handlers of this pass run now; the flush comes later in the pass. */
    fun servePhase() {
        runWaiting()
        if (touched) serve()
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
        if (received > servedUpTo) {
            servedUpTo = received
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

    private var pass = 0L

    private fun retryWrites() {
        pass++
        var i = 0
        while (i < pendingWrites.size) {
            val w = pendingWrites[i]
            // In order within a stream: nothing overtakes a write of the same stream that is still waiting.
            if (w.stream.blockedPass != pass && w.stream.sendSome(w.src, w.fin)) {
                pendingWrites.removeAt(i)
                val c = w.continuation
                if (c != null) w.stream.resumeWriter(c) else w.stream.handedOverDone(w.src)
            } else {
                w.stream.blockedPass = pass
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
            if (loop.settings.fah) {
                // The reply did not come within the pass: the short hold is the fallback.
                loop.settings.holdAck && loop.isLoopThread && establishedSeen && !wasHeld && received > 0 && wrote == 0
            } else {
                loop.settings.holdAck && loop.isLoopThread && establishedSeen && !wasHeld && !heldLast &&
                    received == 1 && deliveredToWaiter > 0 && otherWork == 0
            }
        if (received > 0 && wrote > 0 && !wasHeld) loop.counts.repliedInPass.increment()
        if (wasHeld && wrote > 0) loop.counts.fallbackRides.increment()
        if (hold && sitOut > 0) {
            sitOut--
            hold = false
        }
        // A held flush that leaves because the application wrote is a hit.
        if (wasHeld && otherWork > 0) misses = 0
        received = 0
        servedUpTo = 0
        wrote = 0
        deliveredToWaiter = 0
        otherWork = 0
        heldLast = hold
        if (hold) {
            heldSince = System.nanoTime()
            loop.counts.heldFlushes.increment()
            loop.counts.fallbackHolds.increment()
        }
        return hold
    }
}

/**
 * One bidirectional stream. The suspend edge of the slice: an operation that can finish at once returns
 * without suspending; one that must wait suspends once and is resumed by whichever thread completes it.
 */
internal class SliceStream(
    val conn: SliceConn,
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

    /** Writes handed over with their buffers that quiche has not taken yet. */
    private var handedOverWaiting = 0
    var blockedPass = -1L

    private val deadlineFire = Runnable { waiter.getAndSet(null)?.resume(SliceEnd) }

    private fun arm(): DeadlineNode? =
        if (loop.settings.deadlines) loop.deadlines.arm(System.nanoTime() + OP_TIMEOUT_NANOS, deadlineFire) else null

    private fun disarm(node: DeadlineNode?) {
        if (node != null) loop.deadlines.disarm(node)
    }

    /** Inside the connection: it closed under this stream. */
    fun connectionGone() {
        recvFinished = true
        waiter.getAndSet(null)?.resume(SliceEnd)
    }

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
            val node = arm()
            try {
                return suspendCancellableCoroutine { c ->
                    waiter.set(c)
                    c.invokeOnCancellation { waiter.compareAndSet(c, null) }
                }
            } finally {
                disarm(node)
            }
        }
        if (conn.callerRuns && readable && conn.tryOwn()) {
            // Bytes that arrived before this reader did: it takes them itself.
            loop.counts.enteredByCaller.increment()
            val now = recvNow()
            conn.leave()
            if (now != null) return now
        }
        val node = arm()
        try {
            return suspendCancellableCoroutine { c ->
                waiter.set(c)
                c.invokeOnCancellation { waiter.compareAndSet(c, null) }
                // Otherwise whoever feeds the connection finds this waiter when bytes arrive, and nobody is woken now.
                if (readable || recvFinished) {
                    if (conn.callerRuns) conn.handOff(deliverOp) else loop.execute(deliverTask)
                }
            }
        } finally {
            disarm(node)
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
        if (loop.runsInPlace(c.context)) {
            loop.userCode(conn, c.context[ConnElement]) { c.resume(now) }
        } else {
            loop.counts.resumesAcrossThreads.increment()
            c.resume(now)
        }
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
                return if (loop.settings.result) SliceData(buf) else buf
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
            val node = arm()
            try {
                return suspendCancellableCoroutine { c -> conn.addPending(PendingWrite(this, src, fin, c)) }
            } finally {
                disarm(node)
            }
        }
        if (conn.callerRuns && conn.tryOwn()) {
            // This thread is inside the connection: it calls quiche and sends the datagram itself.
            loop.counts.enteredByCaller.increment()
            conn.adopt(this)
            conn.work()
            if (sendSome(src, fin)) {
                conn.leave()
                return
            }
            val node = arm()
            try {
                return suspendCancellableCoroutine { c ->
                    conn.addPending(PendingWrite(this, src, fin, c))
                    conn.leave()
                }
            } finally {
                disarm(node)
            }
        }
        val node = arm()
        try {
            return suspendCancellableCoroutine { c ->
                if (conn.callerRuns) {
                    conn.handOff { writeInside(src, fin, c) }
                } else {
                    loop.execute {
                        if (loop.enter(conn)) writeInside(src, fin, c) else conn.handOff { writeInside(src, fin, c) }
                    }
                }
            }
        } finally {
            disarm(node)
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
        if (loop.runsInPlace(c.context)) {
            loop.userCode(conn, c.context[ConnElement]) { c.resume(Unit) }
        } else {
            loop.counts.resumesAcrossThreads.increment()
            c.resume(Unit)
        }
    }

    /**
     * Hands [src] (and the FIN, if asked) to the driver and returns at once: the driver sends it and gives the
     * buffer back to the pool. From a handler thread that batches, the write waits in that thread's batch.
     */
    fun send(
        src: NativeBuf,
        fin: Boolean = false,
    ) {
        if (conn.ownedByMe()) {
            sendInside(src, fin)
            return
        }
        val op = Runnable { if (loop.enter(conn)) sendInside(src, fin) else conn.handOff { sendInside(src, fin) } }
        val t = Thread.currentThread()
        if (t is BatchingThread) t.batch(op) else loop.execute(op)
    }

    fun finish() = send(EMPTY, fin = true)

    private fun sendInside(
        src: NativeBuf,
        fin: Boolean,
    ) {
        conn.adopt(this)
        conn.work()
        if (handedOverWaiting == 0 && sendSome(src, fin)) {
            if (src !== EMPTY) loop.release(src)
        } else {
            handedOverWaiting++
            conn.addPending(PendingWrite(this, src, fin, null))
        }
    }

    fun handedOverDone(src: NativeBuf) {
        handedOverWaiting--
        if (src !== EMPTY) loop.release(src)
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

/** A handler thread that collects the writes of one batch of resumes and gives them to the loop together. */
internal abstract class BatchingThread(
    name: String,
) : Thread(name) {
    abstract fun batch(op: Runnable)
}
