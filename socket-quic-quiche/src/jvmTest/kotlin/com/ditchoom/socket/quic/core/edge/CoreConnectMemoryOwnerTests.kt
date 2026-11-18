package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A connect's memory has one owner at a time: the connect until its connection starts, then the connection, whose
 * release on its loop frees it. A connect that fails or is cancelled after the start only ends the connection, so no
 * buffer is freed twice, nor while the loop still sends from it.
 */
class CoreConnectMemoryOwnerTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aConnectCancelledAfterItsConnectionStartedLeavesEachBufferToTheLoopWhichFreesItOnce() =
        runBlocking {
            // A peer that never answers: only the cancelled connect can end the connection.
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
                val factory = FreeCountingFactory(BufferFactory.network())
                val loopThread = CompletableDeferred<Thread>()
                val cancelled = CountDownLatch(1)
                val connect =
                    launch(Dispatchers.IO) {
                        coreConnect(
                            host = "127.0.0.1",
                            port = silent.localPort,
                            serverName = "localhost",
                            options = coreTestOptions,
                            factory = factory,
                            api = platformQuicheApi(),
                            timeout = 60.seconds,
                            readPolicy = ReadPolicy.Bounded(10.seconds),
                            writePolicy = WritePolicy.Bounded(10.seconds),
                            wrapIo = { io -> FirstSendHeld(io, loopThread, cancelled) },
                            loops = loops,
                            flight = TEST_FLIGHT,
                        )
                    }
                // The loop sent the first flight: the connection started, and the loop holds its memory. The loop
                // waits in that send until the cancelled connect has returned, so nothing it does can come first.
                val loop = withTimeout(10.seconds) { loopThread.await() }
                connect.cancelAndJoin()
                cancelled.countDown()

                val unfreed = factory.unfreedAfter(10.seconds)
                val buffers = factory.edgeBuffers()
                assertTrue(buffers.isNotEmpty(), "the connect took its memory through the counting factory")
                assertEquals(emptyList(), unfreed.map { it.capacity }, "edge buffers never freed, by capacity")
                val twice = buffers.filter { it.frees.get() != 1 }.map { it.capacity to it.frees.get() }
                assertEquals(emptyList(), twice, "edge buffers not freed exactly once, as (capacity, frees)")
                val offLoop =
                    buffers
                        .map { it.firstFreedOn.get() }
                        .filter { it !== loop }
                        .map { it.name }
                        .distinct()
                assertEquals(emptyList(), offLoop, "threads other than the loop ${loop.name} that freed the connection's memory")
            }
        }

    /**
     * Completes [sender] with the thread of the first send, the loop's sending the first flight, and holds that thread
     * after the send until [cancelled] opens.
     */
    private class FirstSendHeld(
        private val inner: DatagramBatchIo,
        private val sender: CompletableDeferred<Thread>,
        private val cancelled: CountDownLatch,
    ) : DatagramBatchIo by inner {
        override fun send(batch: SendBatch): SendBatchResult {
            val sent = inner.send(batch)
            if (sender.complete(Thread.currentThread())) cancelled.await(HOLD_SECONDS, TimeUnit.SECONDS)
            return sent
        }
    }

    private companion object {
        const val HOLD_SECONDS = 10L
    }
}
