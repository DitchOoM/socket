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

    /**
     * One server-side receiver: takes at most [part] bytes of each call, checking each is the next of the pattern the
     * client wrote, and blocks [blockMs] in each call. What it leaves is offered again.
     */
    private class Upload(
        private val delivered: AtomicLong,
        private val calls: AtomicLong,
        private val blockMs: Long,
        private val part: Int,
        private val outOfOrder: AtomicLong,
    ) : QuicStreamReceiver {
        val end = CompletableDeferred<ScopedRead.End>()

        override fun onBytes(bytes: ReadBuffer) {
            calls.incrementAndGet()
            val taking = minOf(part, bytes.remaining())
            repeat(taking) {
                val at = delivered.getAndIncrement()
                if (bytes.readByte() != (at % PATTERN).toByte()) outOfOrder.incrementAndGet()
            }
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
                val outOfOrder = AtomicLong()
                withCoreServer(onConnection = { core ->
                    core.public().handleStreams { incoming ->
                        val stream = incoming as QuicStream
                        // The first byte names the stream's receiver: S blocks in every call, anything else does not.
                        // The first byte names the receiver; it is read alone, so the pattern starts with the next.
                        val first = stream.receive.read { it.readByte() }.getOrElse { return@handleStreams }
                        val upload =
                            if (first == SLOW) {
                                Upload(slowDelivered, slowCalls, BLOCK_MS, SLOW_PART, outOfOrder)
                            } else {
                                Upload(fastDelivered, fastCalls, 0, Int.MAX_VALUE, outOfOrder)
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
                            // The marker goes alone, acknowledged by the server's first read, so the pattern starts after it.
                            slowStream.send.write(byteOf(SLOW))
                            kotlinx.coroutines.delay(MARKER_ALONE_MS)
                            val mine = BufferFactory.network().allocate(CHUNK)
                            while (slowQueued.get() < SLOW_TOTAL) {
                                pattern(mine, slowQueued.get())
                                if (slowStream.send.write(mine) !== StreamWrite.Written) return@launch
                                slowQueued.addAndGet(CHUNK.toLong())
                            }
                        }
                    // The neighbour uploads everything while the slow receiver blocks in its calls.
                    fastStream.send.write(byteOf(FAST))
                    kotlinx.coroutines.delay(MARKER_ALONE_MS)
                    var sent = 0L
                    while (sent < FAST_TOTAL) {
                        pattern(chunk, sent)
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
                    // What the slow receiver left in each call is offered again: its calls go on, each taking the next part.
                    withTimeout(WAIT) { while (slowCalls.get() < REOFFERS) kotlinx.coroutines.delay(5) }
                    slowWriter.cancel()
                    assertTrue(outOfOrder.get() == 0L, "every byte a receiver took was the next: ${outOfOrder.get()} out of order")
                    // A call takes at most its part of what it is offered, which can be less than a part.
                    assertTrue(slowDelivered.get() > SLOW_PART, "what the first call left was offered again: ${slowDelivered.get()} B")
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
        const val PATTERN = 251

        /** The slow receiver takes this much of each call and leaves the rest. */
        const val SLOW_PART = 4_096
        const val MARKER_ALONE_MS = 100L
        const val REOFFERS = 5L
        const val CHUNK = 16_384
        const val SLOW_TOTAL = 64L * 1_048_576
        const val FAST_TOTAL = 8L * 1_048_576

        /**
         * The stream's receive window can grow to twice its initial 1 MiB while the receiver drains it, plus the sender's
         * 64 KiB queue, the receiver's 64 KiB lent buffer and a write in flight.
         */
        const val HELD_BOUND = 2L * 1_048_576 + 3L * 65_536
        val WAIT = 60.seconds

        /** Fills [buffer] with the pattern from [offset]: byte i of the stream is i mod [PATTERN]. */
        fun pattern(
            buffer: com.ditchoom.buffer.PlatformBuffer,
            offset: Long,
        ) {
            buffer.resetForWrite()
            repeat(CHUNK) { buffer.writeByte(((offset + it) % PATTERN).toByte()) }
            buffer.resetForRead()
        }

        fun byteOf(value: Byte) =
            BufferFactory.network().allocate(1).apply {
                writeByte(value)
                resetForRead()
            }
    }
}
