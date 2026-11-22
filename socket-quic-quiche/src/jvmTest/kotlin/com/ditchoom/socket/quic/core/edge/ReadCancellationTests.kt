package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Nobody
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A read's cancellation against its delivery: whatever buffer a read takes from the caller's factory is either seen by
 * the caller or freed, and a cancelled read leaves its stream. The caller runs on a dispatcher whose tasks the test runs
 * by hand, so the window between the connection resuming the caller and the caller running is held open on purpose.
 */
class ReadCancellationTests {
    @Test
    fun aReadDeliveredToACallerCancelledBeforeItRanFreesTheBuffer() =
        withTrackedClient { client, factory ->
            val stream = client.connection.openStream()
            val caller = HeldDispatcher()
            val outcome = CompletableDeferred<Result<ReadResult>>()
            val reader = CoroutineScope(caller + Job()).launch { outcome.complete(runCatching { stream.read(30.seconds) }) }
            caller.runAll()
            stream.write(payload(), 10.seconds)
            // The echo resumes the parked read: the resume is a task on the caller's dispatcher, not run yet.
            val resumed = caller.next(10.seconds) ?: fail("the echo never resumed the parked read")
            reader.cancel()
            resumed.run()
            caller.runAll()
            assertIs<CancellationException>(
                outcome.await().exceptionOrNull(),
                "a read resumed after its caller was cancelled: ${outcome.await()}",
            )
            closeAndAssertEveryReadBufferFreed(client, factory)
        }

    @Test
    fun aReadWhoseCallerWasAlreadyCancelledLeavesItsStream() =
        withTrackedClient { client, factory ->
            val stream = client.connection.openStream()
            val caller = HeldDispatcher()
            val outcome = CompletableDeferred<Result<ReadResult>>()
            val job = Job()
            job.cancel()
            CoroutineScope(caller + job).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                outcome.complete(runCatching { stream.read(30.seconds) })
            }
            caller.runAll()
            assertIs<CancellationException>(outcome.await().exceptionOrNull())
            assertEquals(Nobody, readerOf(client, stream.streamId))
            assertEchoes(stream)
            closeAndAssertEveryReadBufferFreed(client, factory)
        }

    @Test
    fun aParkedReadCancelledByATimeoutLeavesItsStreamAndRaisesTheTimeout() =
        withTrackedClient { client, factory ->
            val stream = client.connection.openStream()
            val outcome = runCatching { withTimeout(100.milliseconds) { stream.read(Duration.INFINITE) } }
            assertIs<TimeoutCancellationException>(outcome.exceptionOrNull(), "a read cancelled by withTimeout: $outcome")
            assertEquals(Nobody, readerOf(client, stream.streamId), "the cancelled read still waits on its stream")
            assertEchoes(stream)
            closeAndAssertEveryReadBufferFreed(client, factory)
        }

    /**
     * A cancelled read has left its stream by the time its caller resumes. The caller runs unconfined and is cancelled
     * from a thread with no event loop, so it resumes inside the cancellation itself; what it asks the connection next
     * reaches the connection after the read was handed back, as a read it starts next would.
     */
    @Test
    fun aCancelledReadHasLeftItsStreamWhenItsCallerResumes() =
        withTrackedClient { client, factory ->
            val stream = client.connection.openStream()
            val seenOnResume = CompletableFuture<Any>()
            val reader =
                CoroutineScope(Dispatchers.Unconfined + Job()).launch {
                    try {
                        stream.read(Duration.INFINITE)
                    } catch (cancelled: CancellationException) {
                        seenOnResume.complete(readerOf(client, stream.streamId))
                        throw cancelled
                    }
                }
            assertTrue(eventually(10.seconds) { readerOf(client, stream.streamId) !== Nobody }, "the read never parked")
            thread { reader.cancel() }.join()
            assertEquals(Nobody, seenOnResume.get(10, TimeUnit.SECONDS), "the resumed caller found its cancelled read still on the stream")
            assertEchoes(stream)
            closeAndAssertEveryReadBufferFreed(client, factory)
        }

    @Test
    fun aParkedReadWhoseCallerIsCancelledResumesThatCallerAndLeavesItsStream() =
        withTrackedClient { client, factory ->
            val stream = client.connection.openStream()
            val caller = HeldDispatcher()
            val outcome = CompletableDeferred<Result<ReadResult>>()
            val reader = CoroutineScope(caller + Job()).launch { outcome.complete(runCatching { stream.read(30.seconds) }) }
            caller.runAll()
            reader.cancel()
            val resumed = caller.next(10.seconds) ?: fail("a cancelled parked read never resumed its caller")
            resumed.run()
            caller.runAll()
            assertIs<CancellationException>(outcome.await().exceptionOrNull())
            assertEquals(Nobody, readerOf(client, stream.streamId), "the cancelled read still waits on its stream")
            assertEchoes(stream)
            closeAndAssertEveryReadBufferFreed(client, factory)
        }

    private suspend fun assertEchoes(stream: QuicByteStream) {
        stream.write(payload(), 10.seconds)
        val back = stream.read(10.seconds)
        if (back !is ReadResult.Data) fail("the stream echoed nothing after a cancelled read: $back")
        assertEquals(PAYLOAD, back.buffer.remaining())
        back.buffer.freeIfNeeded()
    }

    private suspend fun closeAndAssertEveryReadBufferFreed(
        client: CoreClient,
        factory: TrackingFactory,
    ) {
        client.connection.close(QuicError.NoError)
        assertTrue(factory.handedOut().isNotEmpty(), "no read took a buffer from the factory")
        val unfreed = factory.handedOut().filterNot { (it as CloseableBuffer).isFreed }
        assertTrue(unfreed.isEmpty(), "${unfreed.size} of ${factory.handedOut().size} read buffers were never freed")
    }

    /** The waiter parked as the reader of [stream], read inside the connection after every work queued before. */
    private fun readerOf(
        client: CoreClient,
        stream: QuicStreamId,
    ): Any {
        val probe = ReaderProbe(stream)
        client.conn.submit(probe, CallerPlace.HandToLoop)
        return probe.reader.get(10, TimeUnit.SECONDS)
    }

    private class ReaderProbe(
        private val stream: QuicStreamId,
    ) : ConnWork() {
        val reader = CompletableFuture<Any>()

        override fun run(inside: Inside) {
            reader.complete(inside.streams.entry(stream).reader)
        }

        override fun gone(end: ConnEnd) {
            reader.complete(end)
        }
    }

    private fun withTrackedClient(body: suspend (CoreClient, TrackingFactory) -> Unit) =
        runBlocking {
            skipOnMissingNativeLib(ReadCancellationTests::class) {
                withEchoServer { port ->
                    val factory = TrackingFactory(BufferFactory.deterministic())
                    body(connectCore(port, factory = factory), factory)
                }
            }
        }

    private fun payload(): PlatformBuffer =
        BufferFactory.deterministic().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    /** Keeps every buffer it hands a read, so each can be asked whether it was freed; the connection's own are not kept. */
    private class TrackingFactory(
        private val delegate: BufferFactory,
    ) : BufferFactory {
        private val buffers: MutableList<PlatformBuffer> = Collections.synchronizedList(mutableListOf())

        fun handedOut(): List<PlatformBuffer> = synchronized(buffers) { buffers.toList() }

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer =
            delegate.allocate(size, byteOrder).also {
                if (Throwable().stackTrace.any { frame -> frame.className == READ_OP }) buffers += it
            }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder)
    }

    /** A caller's dispatcher whose tasks wait until the test runs them. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = tasks.put(block)

        /** The next task, without running it; null when none came within [within]. */
        fun next(within: Duration): Runnable? = tasks.poll(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)

        /** Runs tasks until none is queued. */
        fun runAll() {
            while (true) (tasks.poll() ?: return).run()
        }
    }

    private companion object {
        const val PAYLOAD = 64
        val READ_OP: String = ReadOp::class.java.name
    }
}
