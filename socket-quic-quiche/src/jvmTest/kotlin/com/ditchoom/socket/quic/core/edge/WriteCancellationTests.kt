package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Nobody
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A write's cancellation against the connection's use of the caller's buffer: the connection reads the bytes from the
 * buffer's address and moves its position, so a cancelled write's caller must not get its buffer back while the
 * connection can still touch it. The caller runs on a dispatcher whose tasks the test runs by hand, and the connection
 * is held busy by a work item that blocks inside it, so the window between the cancellation and the connection taking
 * the write back is held open on purpose.
 */
class WriteCancellationTests {
    @Test
    fun aParkedWriteWhoseCallerIsCancelledResumesThatCallerOnlyOnceTheConnectionTookItBack() =
        withClient { client ->
            val stream = client.connection.openStream()
            val caller = HeldDispatcher()
            val outcome = CompletableDeferred<Throwable>()
            val big = buffer(CHUNK)
            val writer =
                CoroutineScope(caller + Job()).launch {
                    // Nothing reads the echo, so the peer stops reading and a write parks once the stream's window is full.
                    try {
                        while (true) {
                            big.position(0)
                            stream.write(big, 30.seconds)
                        }
                    } catch (ended: Throwable) {
                        outcome.complete(ended)
                    }
                }
            parkWriter(client, stream.streamId, caller)
            val busy = Blocker()
            client.conn.submit(busy, CallerPlace.HandToLoop)
            val position =
                try {
                    assertTrue(busy.entered.await(10, TimeUnit.SECONDS), "the blocker never ran inside the connection")
                    // A write answered before the blocker entered resumes now; the writer's next one waits behind the blocker.
                    caller.runAll()
                    writer.cancel()
                    assertNull(
                        caller.next(HELD),
                        "a cancelled write resumed its caller while the connection, busy, had not taken the write back",
                    )
                    big.position()
                } finally {
                    busy.release.countDown()
                }
            val resumed = caller.next(10.seconds) ?: fail("a cancelled parked write never resumed its caller")
            resumed.run()
            caller.runAll()
            assertIs<CancellationException>(outcome.await())
            assertEquals(Nobody, writerOf(client, stream.streamId), "the cancelled write still waits on its stream")
            assertEquals(position, big.position(), "the connection moved the buffer of a write its caller had left")
            client.connection.close(QuicError.NoError)
        }

    @Test
    fun aWriteWhoseCallerWasAlreadyCancelledNeverTouchesItsBuffer() =
        withClient { client ->
            val stream = client.connection.openStream()
            val outcome = CompletableDeferred<Result<com.ditchoom.buffer.flow.BytesWritten>>()
            val job = Job()
            job.cancel()
            val small = buffer(PAYLOAD)
            CoroutineScope(HeldDispatcher() + job).launch(start = CoroutineStart.UNDISPATCHED) {
                outcome.complete(runCatching { stream.write(small, 10.seconds) })
            }
            assertIs<CancellationException>(outcome.await().exceptionOrNull())
            assertEquals(0, small.position(), "a write cancelled before it ran sent bytes")
            assertEquals(Nobody, writerOf(client, stream.streamId))
            assertEchoes(stream)
            client.connection.close(QuicError.NoError)
        }

    /** Runs the writer's tasks until its write waits parked on [stream]. */
    private fun parkWriter(
        client: CoreClient,
        stream: QuicStreamId,
        caller: HeldDispatcher,
    ) {
        val giveUp = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (writerOf(client, stream) === Nobody) {
            if (System.nanoTime() > giveUp) fail("no write parked on a stream whose echo nobody reads")
            caller.next(20.milliseconds)?.run()
        }
    }

    private suspend fun assertEchoes(stream: QuicByteStream) {
        val payload = buffer(PAYLOAD)
        stream.write(payload, 10.seconds)
        val back = stream.read(10.seconds)
        if (back !is com.ditchoom.buffer.flow.ReadResult.Data) fail("the stream echoed nothing after a cancelled write: $back")
        assertEquals(PAYLOAD, back.buffer.remaining())
    }

    /** The waiter parked as the writer of [stream], read inside the connection after every work queued before. */
    private fun writerOf(
        client: CoreClient,
        stream: QuicStreamId,
    ): Any {
        val probe = WriterProbe(stream)
        client.conn.submit(probe, CallerPlace.HandToLoop)
        return probe.writer.get(10, TimeUnit.SECONDS)
    }

    private class WriterProbe(
        private val stream: QuicStreamId,
    ) : ConnWork() {
        val writer = CompletableFuture<Any>()

        override fun run(inside: Inside) {
            writer.complete(inside.streams.entry(stream).writer)
        }

        override fun gone(end: ConnEnd) {
            writer.complete(end)
        }
    }

    /** Holds whoever is inside the connection until released. */
    private class Blocker : ConnWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun run(inside: Inside) {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }

        override fun gone(end: ConnEnd) = entered.countDown()
    }

    private fun withClient(body: suspend (CoreClient) -> Unit) =
        runBlocking {
            skipOnMissingNativeLib(WriteCancellationTests::class) {
                withEchoServer { port -> body(connectCore(port, factory = BufferFactory.network())) }
            }
        }

    private fun buffer(size: Int): PlatformBuffer =
        BufferFactory.network().allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
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
        const val CHUNK = 1 shl 20

        /** How long the test waits to see that a cancelled write did not resume early. */
        val HELD = 300.milliseconds
    }
}
