package com.ditchoom.socket.quic.core.ready

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.NetBinding
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NativeOpened
import com.ditchoom.socket.quic.core.udp.NativeSocket
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.network
import java.net.InetSocketAddress
import java.util.function.IntSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The native poller against the kernel: it wakes when woken, times out no earlier than asked, and reports a socket. */
class NativePollerTests {
    private val shim by lazy { loadNetShim() }

    @Test
    fun theBindingIsTheOneThisRuntimeLoadsQuicheThrough() {
        val expected = if (System.getProperty("quiche.expectedJvmBackend") == "ffm") NetBinding.Ffm else NetBinding.Jni
        assertEquals(expected, shim.binding)
    }

    @Test
    fun aPollerAtATaggedAddressIsOpenedAndOnlyASmallNegatedCodeIsARefusal() {
        // Android tags the top byte of heap pointers: as a signed number the address is negative.
        val tagged =
            object : com.ditchoom.socket.quic.NetShim by shim {
                override fun pollerOpen(capacity: Int): Long = -0x4BFF_FF8E_DCBA_9880L
            }
        assertIs<PollerOpened.Opened>(NativePoller.open(tagged, MonotonicClock, 16))
        val refused =
            object : com.ditchoom.socket.quic.NetShim by shim {
                override fun pollerOpen(capacity: Int): Long = -13L
            }
        assertEquals(
            com.ditchoom.socket.quic.SocketRefusal.ENoMem,
            assertIs<PollerOpened.Refused>(NativePoller.open(refused, MonotonicClock, 16)).refusal,
        )
    }

    @Test
    fun aWakeEndsAWaitThatHasNoDeadline() {
        val poller = open()
        val ready = ReadyTokens(8)
        Thread { poller.wake() }.start()
        assertEquals(WakeCause.Woken, poller.await(NextDue.None, ready))
        poller.close()
    }

    @Test
    fun aTimedWaitEndsAtItsDeadlineAndNotBefore() {
        val poller = open()
        val start = MonotonicClock.now().value
        assertEquals(WakeCause.Deadline, poller.await(NextDue.at(start + 20_000_000L), ReadyTokens(8)))
        assertTrue(MonotonicClock.now().value - start >= 20_000_000L, "woke early")
        poller.close()
    }

    @Test
    fun aDatagramMakesItsSocketsTokenReadyAndWriteInterestReportsWritable() {
        val poller = open()
        val server = socket(SocketPeer.Unconnected)
        val client = socket(SocketPeer.Connected(server.local))
        poller.add(SocketRef(IntSupplier { server.cell.descriptor() }), 3)
        poller.add(SocketRef(IntSupplier { client.cell.descriptor() }), 9)
        poller.interest(9, WriteInterest.Wanted)
        val records = native(NativeIo.SEND_RECORD * 1)
        val send = SendBatch(native(1500), BatchLimits(1, 1500))
        send.commit(100, SendTarget.ConnectedPeer)
        NativeIo(shim, client, listOf(records), native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE)).send(send)
        val ready = ReadyTokens(8)
        val seen = HashSet<Int>()
        val until = System.nanoTime() + 5_000_000_000L
        while (!(3 in seen && 9 in seen) && System.nanoTime() < until) {
            poller.await(NextDue.at(MonotonicClock.now().value + 100_000_000L), ready)
            for (i in 0 until ready.count) seen.add(ready.tokenAt(i))
            Thread.sleep(2)
        }
        assertEquals(setOf(3, 9), seen)
        poller.interest(9, WriteInterest.None)
        poller.remove(3)
        poller.remove(9)
        poller.close()
    }

    @Test
    fun aSecondCloseNeverReachesNativeCodeAndNorDoesAWakeAfterTheClose() {
        assumeNativeStack()
        var closes = 0
        var wakes = 0
        val counting =
            object : com.ditchoom.socket.quic.NetShim by shim {
                override fun pollerClose(poller: Long) {
                    closes++
                    shim.pollerClose(poller)
                }

                override fun pollerWake(poller: Long): Int {
                    wakes++
                    return shim.pollerWake(poller)
                }
            }
        val poller = assertIs<PollerOpened.Opened>(NativePoller.open(counting, MonotonicClock, 16)).poller
        poller.wake()
        poller.close()
        poller.close()
        poller.wake()
        assertEquals(1, closes, "the native poller is freed once")
        assertEquals(1, wakes, "a wake after the close never reaches the freed poller")
    }

    @Test
    fun wakesRacingACloseFreeThePollerExactlyOnceAndNeverAfterItIsFreed() {
        assumeNativeStack()
        repeat(200) {
            val freed =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val afterFree =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val counting =
                object : com.ditchoom.socket.quic.NetShim by shim {
                    override fun pollerClose(poller: Long) {
                        freed.incrementAndGet()
                        shim.pollerClose(poller)
                    }

                    override fun pollerWake(poller: Long): Int {
                        if (freed.get() != 0) afterFree.incrementAndGet()
                        return shim.pollerWake(poller)
                    }
                }
            val poller = assertIs<PollerOpened.Opened>(NativePoller.open(counting, MonotonicClock, 16)).poller
            val start = java.util.concurrent.CountDownLatch(1)
            val wakers =
                List(3) {
                    Thread {
                        start.await()
                        repeat(50) { poller.wake() }
                    }.also(Thread::start)
                }
            start.countDown()
            poller.close()
            wakers.forEach(Thread::join)
            assertEquals(1, freed.get())
            assertEquals(0, afterFree.get())
        }
    }

    private fun open(): NativePoller {
        assumeNativeStack()
        return assertIs<PollerOpened.Opened>(NativePoller.open(shim, MonotonicClock, 16)).poller
    }

    private fun socket(peer: SocketPeer): NativeSocket =
        assertIs<NativeOpened.Opened>(
            NativeSocket.open(
                shim,
                native(NativeSocket.SPEC_BYTES),
                InetSocketAddress("127.0.0.1", 0).toPathKey(),
                peer,
                0,
                0,
                UdpBatching.Newest,
                1,
            ),
        ).socket

    private fun native(size: Int): NativeBytes = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(size))).bytes
}
