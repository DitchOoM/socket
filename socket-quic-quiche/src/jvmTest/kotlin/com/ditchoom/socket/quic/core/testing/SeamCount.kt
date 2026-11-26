package com.ditchoom.socket.quic.core.testing

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/** Whether a [SeamCount] names the caller of each call it counts, which walks the stack and allocates. */
internal enum class Attribution { Off, On }

/**
 * The calls one seam of the core took, on any thread. While [attribution] is [Attribution.On] each call is also
 * filed under its thread and the first core frames that made it, for the breakdown a failed gate prints.
 */
internal class SeamCount {
    private val total = LongAdder()
    private val sites = ConcurrentHashMap<String, LongAdder>()

    @Volatile
    var attribution = Attribution.Off

    fun hit() {
        total.increment()
        when (attribution) {
            Attribution.Off -> Unit
            Attribution.On -> sites.computeIfAbsent(site()) { LongAdder() }.increment()
        }
    }

    fun value(): Long = total.sum()

    /** The attributed calls per [per] operations, most first. */
    fun sites(per: Int): String =
        sites.entries
            .map { it.key to it.value.sum() }
            .sortedByDescending { it.second }
            .take(SITES)
            .joinToString("\n") { (site, n) -> "    %6.2f %s".format(n.toDouble() / per, site) }

    private fun site(): String {
        val frames =
            StackWalker.getInstance().walk { stack ->
                stack
                    .filter { it.className.startsWith(CORE) && !it.className.startsWith(TESTING) }
                    .limit(FRAMES)
                    .map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                    .toList()
            }
        // The thread's name without its trailing index: the loop, the caller, a handler pool.
        val thread = Thread.currentThread().name.replace(TRAILING_INDEX, "")
        return "[$thread] " + frames.joinToString(" < ")
    }

    private companion object {
        const val CORE = "com.ditchoom.socket.quic"
        const val TESTING = "com.ditchoom.socket.quic.core.testing"
        const val FRAMES = 4L
        const val SITES = 8
        val TRAILING_INDEX = Regex("-\\d+$")
    }
}

/** Where the core reads its clock: each site's reads have a bound in work counted alongside them. */
internal enum class ClockSite {
    /** The loop's own: the reading after a pass, and the one after its wait that is the next pass's time. */
    Pass,

    /** The start of a turn the loop serves in its pass. */
    Turn,

    /** A write's start, which quiche's pacer judges it at. */
    Write,

    /** A written datagram whose send time may be the pacer's. */
    SentAt,

    /** The connection's first datagram, which judges whose clock libquiche reads. */
    Judge,

    /** A socket read that brought datagrams. */
    Receive,

    /** A datagram handed to quiche. */
    DatagramIn,

    /** Anything else: an operation's own readings, a caller's entry in place among them. */
    Op,
}

/** Clock readings filed by [ClockSite] while [classifying] is [Attribution.On]: the caller's frames name the site. */
internal class ClockSites {
    private val bySite = ClockSite.entries.associateWith { LongAdder() }

    @Volatile
    var classifying = Attribution.Off

    fun read() {
        when (classifying) {
            Attribution.Off -> Unit
            Attribution.On -> bySite.getValue(site()).increment()
        }
    }

    fun values(): Map<ClockSite, Long> = bySite.mapValues { it.value.sum() }

    private fun site(): ClockSite =
        StackWalker.getInstance().walk { stack ->
            stack
                .filter { it.className.startsWith(CORE) && it.className.substringAfterLast('.') !in WRAPPERS }
                .findFirst()
                .map { siteOf(it.className.substringAfterLast('.'), it.methodName.substringBefore('-')) }
                .orElse(ClockSite.Op)
        }

    private fun siteOf(
        type: String,
        method: String,
    ): ClockSite =
        when (type) {
            "Loop" -> ClockSite.Pass
            "Conn" -> if (method == "turn") ClockSite.Turn else ClockSite.Op
            "Egress" ->
                when (method) {
                    "write", "writePaced" -> ClockSite.Write
                    "sentAt" -> ClockSite.SentAt
                    "judged" -> ClockSite.Judge
                    else -> ClockSite.Op
                }
            "Endpoint" -> if (method == "receiveWaiting") ClockSite.Receive else ClockSite.Op
            "Ingress" -> ClockSite.DatagramIn
            else -> ClockSite.Op
        }

    private companion object {
        const val CORE = "com.ditchoom.socket.quic"

        /** Frames that only pass a reading on: the counting clock, the turn's clock, and a connection's inside. */
        val WRAPPERS = setOf("CountingClock", "ClockSites", "TurnClock", "MonotonicClock")
    }
}

/**
 * [real], with every reading counted in [reads] and filed in [sites]; its base is [real]'s, so libquiche is pinned
 * exactly as without it.
 */
internal class CountingClock(
    private val real: LoopClock,
    val reads: SeamCount = SeamCount(),
    val sites: ClockSites = ClockSites(),
) : LoopClock {
    override fun now(): Nanos {
        reads.hit()
        sites.read()
        return real.now()
    }

    override fun base(): ClockBase = real.base()
}

/**
 * [real], with each batch send and each batch receive counted: one call into the network each. A receive that found
 * nothing is counted in [emptyReceives], every other in [receives].
 */
internal class CountingIo(
    private val real: DatagramBatchIo,
    private val sends: SeamCount,
    private val receives: SeamCount,
    private val emptyReceives: SeamCount,
) : DatagramBatchIo by real {
    override fun send(batch: SendBatch): SendBatchResult {
        sends.hit()
        return real.send(batch)
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        val got = real.receive(into)
        when (got) {
            is ReceiveBatchResult.Nothing -> emptyReceives.hit()
            is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog, is ReceiveBatchResult.Failed -> receives.hit()
        }
        return got
    }
}

/** [real], with each buffer the core takes from it counted in [acquires]; a buffer still goes back to [real]'s pool. */
internal class CountingFactory(
    private val real: BufferFactory,
    val acquires: SeamCount = SeamCount(),
) : BufferFactory by real {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        acquires.hit()
        return real.allocate(size, byteOrder)
    }
}
