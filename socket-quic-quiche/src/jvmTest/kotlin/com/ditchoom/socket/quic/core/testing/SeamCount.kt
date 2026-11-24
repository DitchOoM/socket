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

/** [real], with every reading counted in [reads]; its base is [real]'s, so libquiche is pinned exactly as without it. */
internal class CountingClock(
    private val real: LoopClock,
    val reads: SeamCount = SeamCount(),
) : LoopClock {
    override fun now(): Nanos {
        reads.hit()
        return real.now()
    }

    override fun base(): ClockBase = real.base()
}

/** [real], with each batch send and each batch receive counted: one call into the network each. */
internal class CountingIo(
    private val real: DatagramBatchIo,
    private val sends: SeamCount,
    private val receives: SeamCount,
) : DatagramBatchIo by real {
    override fun send(batch: SendBatch): SendBatchResult {
        sends.hit()
        return real.send(batch)
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        receives.hit()
        return real.receive(into)
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
