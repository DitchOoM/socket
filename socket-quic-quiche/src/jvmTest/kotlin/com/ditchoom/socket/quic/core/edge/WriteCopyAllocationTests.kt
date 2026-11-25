package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.stream.code
import com.ditchoom.socket.quic.core.stream.sender
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.management.ManagementFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What a write's copy costs, counted: 16 MiB one way in 1 KiB and 64 KiB writes through (a) the public write, which
 * copies into the stream's send queue before quiche copies it again; (b) the zero-copy write the public path had before
 * the queue (quiche reads the caller's buffer in place); (c) a send buffer lent from the connection's pool, filled by
 * the caller and handed over, which the loop gives quiche in place and returns to the pool once quiche took it
 * (test-only). Every row fills its buffer from the same source first, as an application producing the bytes would.
 * Per write: the bytes the caller's thread and the loop allocate, the caller's resumes; per MiB: the CPU both threads
 * spent. Reported, not gated.
 */
class WriteCopyAllocationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    private enum class Shape(
        val label: String,
    ) {
        Queue("(a) copy into the send queue (public write)"),
        InPlace("(b) zero-copy, quiche reads the caller's buffer (pre-#1050 write)"),
        HandOver("(c) pool-lent buffer handed over, recycled after quiche took it"),
    }

    @Test
    fun theWriteShapesAreCounted() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(WriteCopyAllocationTests::class) {
                    withCoreServer(onConnection = { drain(it) }) { server ->
                        for (size in listOf(1_024, 65_536)) {
                            for (shape in Shape.values()) row(server.port, shape, size)
                        }
                    }
                }
            }
        }

    private suspend fun drain(connection: CoreConnection) {
        connection.handleStreams { stream ->
            while (true) {
                when (val read = stream.read(10.seconds)) {
                    is ReadResult.Data -> read.buffer.freeIfNeeded()
                    else -> break
                }
            }
            stream.close()
        }
    }

    private suspend fun row(
        port: Int,
        shape: Shape,
        size: Int,
    ) {
        val client = connectCore(port, loops = loops, factory = BufferFactory.network())
        val dispatcher = Counting(CALLER)
        val done = CompletableFuture<kotlin.Result<String>>()
        suspend {
            val source =
                BufferFactory.network().allocate(size).apply {
                    repeat(size) { writeByte(it.toByte()) }
                    resetForRead()
                }
            val writes = TOTAL / size
            val write: suspend () -> Unit =
                when (shape) {
                    Shape.Queue -> {
                        val stream =
                            client.connection
                                .public()
                                .openStream()
                                .getOrElse { fail("not opened: $it") }
                        val mine = BufferFactory.network().allocate(size)
                        val w: suspend () -> Unit = {
                            fill(mine, source)
                            if (stream.send.write(mine) !== StreamWrite.Written) fail("not written")
                        }
                        w
                    }
                    Shape.InPlace -> {
                        val stream = client.connection.openStream()
                        val mine = BufferFactory.network().allocate(size)
                        val w: suspend () -> Unit = {
                            fill(mine, source)
                            while (mine.remaining() > 0) stream.write(mine, 10.seconds)
                        }
                        w
                    }
                    Shape.HandOver -> {
                        val halves = client.connection.halves.openBidirectional()
                        val conn = CONN_FIELD.get(client.connection) as Conn
                        val outbox = HandOverOutbox(halves.id, conn)
                        val w: suspend () -> Unit = {
                            val lent = client.connection.bufferFactory.allocate(size)
                            fill(lent, source)
                            outbox.send(lent)
                        }
                        w
                    }
                }
            repeat(WARM_WRITES) { write() }
            val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val me = Thread.currentThread().id
            val loop = threadId(client.loop.name)
            val callerBytes = mx.getThreadAllocatedBytes(me)
            val loopBytes = mx.getThreadAllocatedBytes(loop)
            val callerCpu = mx.getThreadCpuTime(me)
            val loopCpu = mx.getThreadCpuTime(loop)
            val resumes = dispatcher.dispatches.get()
            repeat(writes) { write() }
            val n = writes.toDouble()
            val mib = (writes.toLong() * size).toDouble() / 1_048_576
            "%s | %d B writes | caller %.1f B/write | loop %.1f B/write | resumes %.3f/write | caller CPU %.2f ms/MiB | loop CPU %.2f ms/MiB"
                .format(
                    shape.label,
                    size,
                    (mx.getThreadAllocatedBytes(me) - callerBytes) / n,
                    (mx.getThreadAllocatedBytes(loop) - loopBytes) / n,
                    (dispatcher.dispatches.get() - resumes) / n,
                    (mx.getThreadCpuTime(me) - callerCpu) / 1e6 / mib,
                    (mx.getThreadCpuTime(loop) - loopCpu) / 1e6 / mib,
                )
        }.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
        println("[write copy] " + done.get(300, TimeUnit.SECONDS).getOrThrow())
        client.connection.close(QuicError.NoError)
        dispatcher.close()
    }

    private fun fill(
        into: PlatformBuffer,
        from: PlatformBuffer,
    ) {
        into.resetForWrite()
        from.position(0)
        into.write(from)
        into.resetForRead()
    }

    /**
     * Shape (c), test-only: buffers handed over whole. The loop gives quiche each in place, in order, and frees it back to
     * its pool once quiche took every byte; the sender waits for a slot when [SLOTS] buffers are queued. One sender.
     */
    private class HandOverOutbox(
        private val stream: QuicStreamId,
        private val conn: Conn,
    ) {
        private val slots = AtomicReferenceArray<PlatformBuffer>(SLOTS)
        private val head = AtomicLong(0L)
        private val tail = AtomicLong(0L)
        private val scheduled = AtomicInteger(0)
        private val waiting =
            java.util.concurrent.atomic
                .AtomicReference<Any>(NOBODY)
        private val feed = Feed()

        suspend fun send(buffer: PlatformBuffer) {
            while (tail.get() - head.get() == SLOTS.toLong()) {
                suspendCancellableCoroutine<Unit> { c ->
                    waiting.set(c)
                    if (tail.get() - head.get() < SLOTS && waiting.compareAndSet(c, NOBODY)) c.resume(Unit)
                }
            }
            val t = tail.get()
            slots.set((t % SLOTS).toInt(), buffer)
            tail.set(t + 1)
            if (scheduled.compareAndSet(0, 1)) conn.submit(feed, CallerPlace.HandToLoop)
        }

        private inner class Feed :
            ConnWork(),
            Waiter {
            override fun run(inside: Inside) {
                scheduled.set(0)
                if (drain(inside.streams) == Retry.Again) inside.streams.entry(stream).parkWriter(this)
            }

            override fun retry(table: StreamTable): Retry = drain(table)

            override fun connectionEnded(end: ConnEnd) = Unit

            override fun gone(end: ConnEnd) = Unit

            private fun drain(table: StreamTable): Retry {
                val send = (table.sender() as? Streams.Open)?.send ?: return Retry.Settled
                while (head.get() < tail.get()) {
                    val at = (head.get() % SLOTS).toInt()
                    val buffer = slots.get(at)
                    val address =
                        when (val a = NativeBytes.addressOf(buffer)) {
                            is ReadAddress.At -> a.address
                            is ReadAddress.NotAddressable -> fail("the pool's buffer has no address")
                        }
                    val code = send.send(stream, address + buffer.position(), buffer.remaining(), WriteEnd.More).code()
                    if (code > 0) buffer.position(buffer.position() + code)
                    if (code == 0 || code == QUICHE_ERR_DONE || buffer.remaining() > 0) {
                        table.wantWritable(stream)
                        return Retry.Again
                    }
                    if (code < 0) fail("quiche refused the send: $code")
                    slots.set(at, null)
                    buffer.freeIfNeeded()
                    head.incrementAndGet()
                    val w = waiting.get()
                    @Suppress("UNCHECKED_CAST")
                    if (w !== NOBODY && waiting.compareAndSet(w, NOBODY)) (w as CancellableContinuation<Unit>).resume(Unit)
                }
                return Retry.Settled
            }
        }

        private companion object {
            const val SLOTS = 64
            val NOBODY = Any()
        }
    }

    /** A caller's dispatcher on its own thread, counting dispatches: each one a continuation the loop resumed. */
    private class Counting(
        name: String,
    ) : CoroutineDispatcher() {
        private val queue = ArrayBlockingQueue<Runnable>(64)
        val dispatches = AtomicLong()
        private val thread =
            Thread({
                try {
                    while (true) queue.take().run()
                } catch (_: InterruptedException) {
                    // closed
                }
            }, name).apply {
                isDaemon = true
                start()
            }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches.incrementAndGet()
            queue.put(block)
        }

        fun close() = thread.interrupt()
    }

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private companion object {
        const val CALLER = "write-copy-caller"
        const val TOTAL = 16 * 1_048_576
        const val WARM_WRITES = 256
        val CONN_FIELD = CoreConnection::class.java.getDeclaredField("conn").apply { isAccessible = true }
    }
}
