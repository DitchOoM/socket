package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamReceiver
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A receiver that blocks in [QuicStreamReceiver.onBytes] holds back its own stream's peer, through the stream's shut
 * flow-control window, with bounded memory, and slows only its own connection's handler lane: a neighbour connection's
 * upload on the same server finishes while it blocks. Counted, not timed: what the slow stream's peer got to queue
 * against what its receiver was given.
 */
class ReceiverBackpressureTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** One server-side receiver: counts what it is given, blocking [blockMs] in each call. */
    private class Upload(
        private val delivered: AtomicLong,
        private val calls: AtomicLong,
        private val blockMs: Long,
    ) : QuicStreamReceiver {
        val end = CompletableDeferred<ScopedRead.End>()

        override fun onBytes(bytes: ReadBuffer) {
            calls.incrementAndGet()
            delivered.addAndGet(bytes.remaining().toLong())
            if (blockMs > 0) Thread.sleep(blockMs)
        }

        override fun onEnd(end: ScopedRead.End) {
            this.end.complete(end)
        }
    }

    @Test
    fun aReceiverThatBlocksHoldsBackOnlyItsOwnStreamsPeer() =
        runBlocking {
            skipOnMissingNativeLib(ReceiverBackpressureTests::class) {
                val slowDelivered = AtomicLong()
                val slowCalls = AtomicLong()
                val fastDelivered = AtomicLong()
                val fastCalls = AtomicLong()
                withCoreServer(onConnection = { core ->
                    core.public().handleStreams { incoming ->
                        val stream = incoming as QuicStream
                        // The first byte names the stream's receiver: S blocks in every call, anything else does not.
                        val (first, rest) =
                            stream.receive.read { it.get(it.position()) to (it.remaining() - 1) }.getOrElse { return@handleStreams }
                        val upload =
                            if (first == SLOW) {
                                slowDelivered.addAndGet(rest.toLong())
                                Upload(slowDelivered, slowCalls, BLOCK_MS)
                            } else {
                                fastDelivered.addAndGet(rest.toLong())
                                Upload(fastDelivered, fastCalls, 0)
                            }
                        stream.receive.receive(upload)
                        upload.end.await()
                    }
                }) { server ->
                    val slow = connectCore(server.port, loops = loops, factory = BufferFactory.network()).connection.public()
                    val fast = connectCore(server.port, loops = loops, factory = BufferFactory.network()).connection.public()
                    val slowStream = slow.openStream().getOrElse { fail("not opened: $it") }
                    val fastStream = fast.openStream().getOrElse { fail("not opened: $it") }
                    val slowQueued = AtomicLong()
                    val chunk = BufferFactory.network().allocate(CHUNK)
                    val slowWriter =
                        launch(Dispatchers.Default) {
                            slowStream.send.write(byteOf(SLOW))
                            val mine = BufferFactory.network().allocate(CHUNK)
                            while (slowQueued.get() < SLOW_TOTAL) {
                                mine.resetForWrite()
                                mine.position(CHUNK)
                                mine.resetForRead()
                                if (slowStream.send.write(mine) !== StreamWrite.Written) return@launch
                                slowQueued.addAndGet(CHUNK.toLong())
                            }
                        }
                    // The neighbour uploads everything while the slow receiver blocks in its calls.
                    fastStream.send.write(byteOf(FAST))
                    var sent = 0L
                    while (sent < FAST_TOTAL) {
                        chunk.resetForWrite()
                        chunk.position(CHUNK)
                        chunk.resetForRead()
                        if (fastStream.send.write(chunk) !== StreamWrite.Written) fail("the neighbour's write failed")
                        sent += CHUNK
                    }
                    fastStream.send.finish()
                    withTimeout(WAIT) { while (fastDelivered.get() < FAST_TOTAL) kotlinx.coroutines.delay(5) }
                    val queued = slowQueued.get()
                    val given = slowDelivered.get()
                    println(
                        "[receiver backpressure] neighbour: %d B in %d calls, done; slow: peer queued %d B, receiver given %d B in %d calls (%d ms each), held back %d B"
                            .format(fastDelivered.get(), fastCalls.get(), queued, given, slowCalls.get(), BLOCK_MS, queued - given),
                    )
                    slowWriter.cancel()
                    assertTrue(queued < SLOW_TOTAL, "the slow stream's peer was held back: it queued $queued of $SLOW_TOTAL")
                    assertTrue(
                        queued - given <= HELD_BOUND,
                        "what waits for the slow receiver is bounded by its window and buffers: ${queued - given} B (bound $HELD_BOUND)",
                    )
                    slow.close()
                    fast.close()
                }
            }
        }

    private companion object {
        const val SLOW: Byte = 'S'.code.toByte()
        const val FAST: Byte = 'F'.code.toByte()
        const val BLOCK_MS = 50L
        const val CHUNK = 16_384
        const val SLOW_TOTAL = 64L * 1_048_576
        const val FAST_TOTAL = 8L * 1_048_576

        /**
         * The stream's receive window can grow to twice its initial 1 MiB while the receiver drains it, plus the sender's
         * 64 KiB queue, the receiver's 64 KiB lent buffer and a write in flight.
         */
        const val HELD_BOUND = 2L * 1_048_576 + 3L * 65_536
        val WAIT = 60.seconds

        fun byteOf(value: Byte) =
            BufferFactory.network().allocate(1).apply {
                writeByte(value)
                resetForRead()
            }
    }
}
