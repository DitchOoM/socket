package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testLoopMemory
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * quiche reads a connection's datagrams in the order its socket received them, whoever is inside when each arrives: one
 * the loop copied for a caller inside reaches quiche before the next, even when that caller left before the loop could
 * hand it the copy, and the loop entered for the next.
 */
class ReceiveOrderTests {
    /** Once [arm]ed, one receive returns a datagram of [FIRST] bytes then one of [SECOND]; nothing otherwise. */
    private class TwoArrive : DatagramBatchIo by FakeIo() {
        @Volatile var armed = false

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            if (!armed) return ReceiveBatchResult.Nothing
            armed = false
            for (length in listOf(FIRST, SECOND)) into.received(into.take() as ReceiveBuffer, length, PEER, ArrivedOn.Unreported)
            return ReceiveBatchResult.Got
        }
    }

    /** Network memory; the first allocation, the loop's copy of [FIRST], runs [during] before it returns. */
    private class Hooked(
        private val during: () -> Unit,
    ) : BufferFactory by BufferFactory.Default {
        @Volatile private var hooked = true

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            if (hooked) {
                hooked = false
                during()
            }
            return BufferFactory.Default.allocate(size, byteOrder)
        }
    }

    /** Runs inside on a caller's thread: says it is inside, then waits to be let go. */
    private class Parked(
        private val inside: CountDownLatch,
        private val letGo: CountDownLatch,
    ) : ConnWork() {
        override fun run(inside: Inside) {
            this.inside.countDown()
            letGo.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        override fun gone(end: ConnEnd) = Unit
    }

    @Test
    fun aDatagramCopiedForACallerThatLeftReachesQuicheBeforeTheNextOne() {
        val api = StubQuicheApi()
        val read = CopyOnWriteArrayList<Int>()
        api.onConnRecv = { length -> if (length == FIRST || length == SECOND) read += length }
        val inside = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val left = CountDownLatch(1)
        // The loop copies the first datagram for the caller inside; while it makes the copy's memory, the caller leaves.
        val factory =
            Hooked {
                letGo.countDown()
                assertTrue(left.await(WAIT_SECONDS, TimeUnit.SECONDS), "the caller left")
            }
        val io = TwoArrive()
        val rig =
            ConnRig(
                api,
                io = io,
                lanes =
                    CallerLanes.Of(
                        FixedPool(1) { CallerSend(it, SendBatch(nativeBytes(1_500), BatchLimits(1, 1_500), lane = it + 1)) },
                    ),
                memory = ConnMemory(testLoopMemory(copies = 0), factory, nativeBytes(255)),
            )
        val caller =
            thread {
                rig.conn.submit(Parked(inside, letGo), CallerPlace.MayEnter)
                left.countDown()
            }
        assertTrue(inside.await(WAIT_SECONDS, TimeUnit.SECONDS), "the caller entered in place")
        io.armed = true
        rig.pass(START + STEP)
        caller.join(WAIT_SECONDS * MILLIS)
        rig.pass(START + 2 * STEP)
        assertEquals(
            1L,
            rig.counts.enteredInPlace,
            "the caller entered in place: read $read, copied ${rig.counts.datagramsCopied}",
        )
        assertTrue(rig.counts.datagramsCopied >= 1L, "the first datagram was copied for the caller inside: read $read")
        assertEquals(listOf(FIRST, SECOND), read.toList(), "quiche read the datagrams in the order they arrived")
    }

    private companion object {
        const val FIRST = 100
        const val SECOND = 200
        const val START = 1_000_000L
        const val STEP = 1_000_000L
        const val WAIT_SECONDS = 10L
        const val MILLIS = 1_000L
        val PEER = PathKey(4, 443, 0L, 0x7F000001L)
    }
}
