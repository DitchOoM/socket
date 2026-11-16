package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Failure containment on a core connection against today's server: what one stream operation throws fails that
 * operation, what processing one connection throws ends that connection, and only the loop's own failures and
 * `Error`s end every connection.
 */
class CoreFailureTierTests {
    @Test
    fun anOperationThatThrowsFailsItsCallerAndLeavesTheOtherStreamsWorking() =
        withRig { rig ->
            val connection = rig.connection
            val broken = connection.openStream()
            val healthy = connection.openStream()
            val planted = IllegalStateException("planted in a stream operation")

            val failure =
                assertFailsWith<IllegalStateException> {
                    suspendCancellableCoroutine<ReadResult> { cont ->
                        rig.conn.submit(ReadOp(broken.streamId, OpDeadline(Duration.INFINITE), cont, rig.conn, ThrowingFactory(planted)))
                    }
                }
            assertTrue(failure === planted || failure.cause === planted, "the caller got the planted exception: $failure")

            val payload = Random(1).nextBytes(1024)
            assertContentEquals(payload, echo(healthy, payload), "echo on the other stream, same connection")
            assertIs<EdgeEnd.Open>(connection.end(), "the connection did not end")
        }

    @Test
    fun aConnectionWorkThatThrowsEndsThatConnectionAndTheLoopKeepsRunning() =
        withRig { rig ->
            val stream = rig.connection.openStream()
            val planted = IllegalStateException("planted in connection work")
            val parkedRead = async { runCatching { stream.read(10.seconds) { } } }
            kotlinx.coroutines.delay(200)

            rig.conn.submit(ThrowingWork(planted))

            val end = assertIs<ConnEnd.ProcessingFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
            val read = assertIs<QuicCloseException>(parkedRead.await().exceptionOrNull())
            assertSame(planted, read.cause)
            assertEquals(QuicCloseCircumstance.ProcessingFailed, read.circumstance)
            assertEquals(QuicCloseReason.ByLocal(QuicError.ProcessingFailed), read.closeReason)

            val first = rig.runOnLoop()
            val second = rig.runOnLoop()
            assertTrue(second > first, "the loop kept passing: $first then $second")
            val later = LateWork()
            rig.conn.submit(later)
            assertEquals(1, later.gone.get(5, TimeUnit.SECONDS), "work for the failed connection is answered as gone")
        }

    @Test
    fun anOperationQueuedBehindTheWorkThatEndedTheConnectionFailsWithThatCause() =
        withRig { rig ->
            val stream = rig.connection.openStream()
            val planted = IllegalStateException("planted in connection work")
            val hold = HoldThenThrow(planted)
            rig.conn.submit(hold)
            hold.inside.get(10, TimeUnit.SECONDS)
            // The loop is inside the connection, so the read is queued behind the work that is about to end it.
            val queued = rig.conn.counts.leftForHolder
            val read = async(Dispatchers.IO) { runCatching { stream.read(10.seconds) { } } }
            while (rig.conn.counts.leftForHolder == queued) Thread.yield() // Thread.onSpinWait does not exist on Android API 28
            hold.release.countDown()

            val failure = assertIs<QuicCloseException>(read.await().exceptionOrNull())
            assertSame(planted, failure.cause, "the read ended with the connection's own failure: $failure")
            assertEquals(QuicCloseCircumstance.ProcessingFailed, failure.circumstance)
        }

    @Test
    fun aLoopThatFailsEndsEveryConnectionWithTheLoopFailure() =
        withRig { rig ->
            // On a shared loop an io that throws ends its own socket's connection only (SharedClientLoopTests); what
            // the loop itself throws ends every connection on it.
            val planted = IllegalStateException("planted in the loop")
            rig.loop.port.submit(ThrowingLoopWork(planted))

            val end = assertIs<ConnEnd.LoopFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
        }

    @Test
    fun anOperationStartedAfterTheConnectionEndedFailsInsteadOfWaiting() =
        withRig { rig ->
            val stream = rig.connection.openStream()
            rig.loop.port.submit(ThrowingLoopWork(IllegalStateException("planted in the loop")))
            assertIs<ConnEnd.LoopFailed>(rig.ended())

            assertFailsWith<QuicCloseException> { kotlinx.coroutines.withTimeout(5.seconds) { stream.read(10.seconds) { } } }
            assertFailsWith<QuicCloseException> { kotlinx.coroutines.withTimeout(5.seconds) { rig.connection.openStream() } }
        }

    @Test
    fun anErrorFromConnectionProcessingIsNotContained() =
        withRig { rig ->
            val planted = PlantedError()
            val reported =
                reportedOnTheLoop {
                    rig.conn.submit(ThrowingErrorWork(planted))
                    val end = assertIs<ConnEnd.LoopFailed>(rig.ended())
                    assertSame(planted, end.thrown.throwable)
                }
            assertSame(planted, reported, "the Error was reported on the loop's thread, not contained")
        }

    @Test
    fun aConnectionWorkThatThrowsInsideACallerEndsThatConnectionAndTheCallerLeaves() =
        withRig { rig ->
            val planted = IllegalStateException("planted in connection work, run by a caller")
            runOnACaller(rig) { OnACaller(planted) }

            val end = assertIs<ConnEnd.ProcessingFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
            assertTrue(rig.conn.counts.enteredInPlace >= 1, "a caller ran the work, not the loop")
            val later = LateWork()
            rig.conn.submit(later, CallerPlace.MayEnter)
            assertEquals(1, later.gone.get(5, TimeUnit.SECONDS), "work for the failed connection is answered as gone, so nobody holds it")
            assertTrue(rig.runOnLoop() > 0, "the loop kept running")
        }

    @Test
    fun anErrorInsideACallerEndsThatConnectionIsReportedAndTheCallerLeaves() =
        withRig { rig ->
            val planted = PlantedError()
            val reported = CompletableFuture<Throwable>()
            runOnACaller(rig, reported) { OnACaller(planted) }

            val end = assertIs<ConnEnd.CallerFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
            assertSame(planted, reported.get(5, TimeUnit.SECONDS), "the Error was reported on the caller's thread")
            val later = LateWork()
            rig.conn.submit(later, CallerPlace.MayEnter)
            assertEquals(1, later.gone.get(5, TimeUnit.SECONDS), "the caller left: later work is answered as gone")
            assertTrue(rig.runOnLoop() > 0, "the loop kept running")
        }

    @Test
    fun aFullSocketHandsTheCallersDatagramsToTheLoopWhichSendsThem() =
        withRig { rig ->
            val stream = rig.connection.openStream()
            val payload = Random(9).nextBytes(20_000)
            val head = BufferFactory.Default.allocate(HEAD_BYTES)
            head.writeBytes(payload, 0, HEAD_BYTES)
            head.resetForRead()
            rig.io.refuseCallerSends(1)
            // A caller sends the first bytes in place: the full socket refuses them, and the loop must finish the send.
            sendOnACaller(rig, QuicStreamId(0), head)
            assertEquals(1L, rig.loop.callerCounts.sendsDeferred, "the caller's send was deferred to the loop")
            val received = ByteArrayOutputStream()
            kotlinx.coroutines.coroutineScope {
                val reader =
                    async {
                        while (received.size() < payload.size) {
                            if (stream.read(10.seconds) { received.write(it.readByteArray(it.remaining())) } !is ScopedRead.Data<*>) break
                        }
                    }
                stream.writeFully(
                    BufferFactory.Default.allocate(payload.size - HEAD_BYTES).apply {
                        writeBytes(payload, HEAD_BYTES, payload.size - HEAD_BYTES)
                        resetForRead()
                    },
                    10.seconds,
                )
                reader.await()
            }
            assertContentEquals(payload, received.toByteArray(), "echo of what the loop sent after the socket refused the caller")
        }

    /** Sends [bytes] on [stream] from a caller thread, not the loop: work that finds the loop inside runs there and is tried again. */
    private fun sendOnACaller(
        rig: Rig,
        stream: QuicStreamId,
        bytes: com.ditchoom.buffer.ReadBuffer,
    ) {
        repeat(1_000) {
            val work = SendOnACaller(stream, bytes)
            val caller = Thread { rig.conn.submit(work, CallerPlace.MayEnter) }
            caller.start()
            caller.join(10_000)
            work.settled.get(10, TimeUnit.SECONDS)
            if (work.sent) return
        }
        throw AssertionError("no caller ever sent")
    }

    /**
     * Submits the work [make] builds from a caller thread until a caller, not the loop, ran it: work that finds the loop
     * inside runs on the loop, where [OnACaller] declines.
     */
    private fun runOnACaller(
        rig: Rig,
        reported: CompletableFuture<Throwable> = CompletableFuture(),
        make: () -> OnACaller,
    ) {
        repeat(100) {
            val work = make()
            val caller = Thread { rig.conn.submit(work, CallerPlace.MayEnter) }
            caller.setUncaughtExceptionHandler { _, error -> reported.complete(error) }
            caller.start()
            caller.join(10_000)
            work.settled.get(10, TimeUnit.SECONDS)
            if (work.ranOnACaller) return
        }
        throw AssertionError("the work never ran on a caller")
    }

    private suspend fun echo(
        stream: QuicByteStream,
        payload: ByteArray,
    ): ByteArray = echoOver(stream, payload)

    private fun withRig(body: suspend CoroutineScope.(Rig) -> Unit) =
        runBlocking {
            skipOnMissingNativeLib(CoreFailureTierTests::class) {
                withEchoServer { port ->
                    withContext(Dispatchers.IO) {
                        val rig = Rig.start(port)
                        try {
                            kotlinx.coroutines.withTimeout(30.seconds) { body(rig) }
                        } finally {
                            rig.stop()
                        }
                    }
                }
            }
        }

    private companion object {
        const val HEAD_BYTES = 1_000
    }
}

private class PlantedError : Error()

/** Answers every allocation by throwing what it was given: a read's attempt throws inside the connection. */
private class ThrowingFactory(
    private val planted: Exception,
) : BufferFactory by BufferFactory.Default {
    override fun allocate(
        size: Int,
        byteOrder: com.ditchoom.buffer.ByteOrder,
    ): PlatformBuffer = throw planted
}

private class ThrowingLoopWork(
    private val planted: Exception,
) : LoopWork() {
    override fun run(port: LoopPort) = throw planted

    override fun gone(end: ConnEnd) = Unit
}

/**
 * Runs [body] with the process's default uncaught-exception handler replaced, and answers what a loop thread reported
 * through it: an `Error` is not contained, and on Android the default handler would end the test process.
 */
private suspend fun reportedOnTheLoop(body: suspend () -> Unit): Throwable {
    val reported = CompletableFuture<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        if (thread.name.startsWith(LOOP_THREAD)) reported.complete(error) else previous?.uncaughtException(thread, error)
    }
    try {
        body()
        return reported.get(5, TimeUnit.SECONDS)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}

/** Answers on the loop thread with the number of passes the loop has made. */
private class PassesWork(
    private val loop: Loop,
) : LoopWork() {
    val passes = CompletableFuture<Long>()

    override fun run(port: LoopPort) {
        passes.complete(loop.counts.passes)
    }

    override fun gone(end: ConnEnd) {
        passes.completeExceptionally(IllegalStateException("the loop had stopped"))
    }
}

private class ThrowingWork(
    private val planted: Exception,
) : ConnWork() {
    override fun run(inside: Inside) = throw planted

    override fun gone(end: ConnEnd) = Unit
}

/** Holds the connection from inside until released, then throws what it is given. */
private class HoldThenThrow(
    private val planted: Exception,
) : ConnWork() {
    val inside = CompletableFuture<Unit>()
    val release = java.util.concurrent.CountDownLatch(1)

    override fun run(inside: Inside) {
        this.inside.complete(Unit)
        release.await()
        throw planted
    }

    override fun gone(end: ConnEnd) {
        inside.complete(Unit)
    }
}

private class ThrowingErrorWork(
    private val planted: Error,
) : ConnWork() {
    override fun run(inside: Inside) = throw planted

    override fun gone(end: ConnEnd) = Unit
}

private fun onTheLoop(): Boolean = Thread.currentThread().name.startsWith(LOOP_THREAD)

/** Throws what it is given when a caller runs it, and does nothing when the loop does. */
private class OnACaller(
    private val planted: Throwable,
) : ConnWork() {
    val settled = CompletableFuture<Unit>()

    @Volatile
    var ranOnACaller = false

    override fun run(inside: Inside) {
        ranOnACaller = !onTheLoop()
        settled.complete(Unit)
        if (ranOnACaller) throw planted
    }

    override fun gone(end: ConnEnd) {
        settled.complete(Unit)
    }
}

/** Writes [bytes] to [stream] when a caller runs it, and does nothing when the loop does. */
private class SendOnACaller(
    private val stream: QuicStreamId,
    private val bytes: com.ditchoom.buffer.ReadBuffer,
) : ConnWork() {
    val settled = CompletableFuture<Unit>()

    @Volatile
    var sent = false

    override fun run(inside: Inside) {
        if (!onTheLoop()) {
            val address = (NativeBytes.addressOf(bytes) as ReadAddress.At).address
            inside.streams.io.send(stream, address, bytes.remaining(), WriteEnd.More)
            sent = true
        }
        settled.complete(Unit)
    }

    override fun gone(end: ConnEnd) {
        settled.complete(Unit)
    }
}

private class LateWork : ConnWork() {
    val gone = CompletableFuture<Int>()

    override fun run(inside: Inside) {
        gone.completeExceptionally(IllegalStateException("ran on a failed connection"))
    }

    override fun gone(end: ConnEnd) {
        gone.complete(1)
    }
}

private sealed interface Plant {
    object None : Plant

    class Throws(
        val failure: Throwable,
    ) : Plant
}

/** The real socket's io, until a failure is planted: then its next receive throws. */
private class FlakyIo(
    private val real: DatagramBatchIo,
) : DatagramBatchIo by real {
    @Volatile
    var plant: Plant = Plant.None
    private val refusals =
        java.util.concurrent.atomic
            .AtomicInteger()

    /** The next [count] sends from a thread other than the loop's are refused as a full socket, taking nothing. */
    fun refuseCallerSends(count: Int) = refusals.set(count)

    override fun send(batch: SendBatch): SendBatchResult =
        if (!onTheLoop() && refusals.getAndDecrement() > 0) {
            SendBatchResult.Partial(0, UdpError.WouldBlock)
        } else {
            real.send(batch)
        }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult =
        when (val now = plant) {
            Plant.None -> real.receive(into)
            is Plant.Throws -> throw now.failure
        }
}

/** A core client on a loop of its own, so a failure planted in its loop ends no other test's connections. */
private class Rig(
    val connection: CoreConnection,
    val conn: Conn,
    val loop: Loop,
    val io: FlakyIo,
    private val loops: LoopSet,
) {
    /** How the connection ended, waiting up to ten seconds for it. */
    fun ended(): ConnEnd {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            when (val end = connection.end()) {
                is EdgeEnd.Ended -> return end.end
                is EdgeEnd.Open -> Thread.sleep(5)
            }
        }
        throw AssertionError("the connection did not end")
    }

    /** The loop's pass count as the loop thread sees it. */
    fun runOnLoop(): Long {
        val work = PassesWork(loop)
        loop.port.submit(work)
        return work.passes.get(5, TimeUnit.SECONDS)
    }

    fun stop() = loops.close()

    companion object {
        suspend fun start(port: Int): Rig {
            val loops = LoopSet(1)
            val flaky = arrayOfNulls<FlakyIo>(1)
            val client = connectCore(port, loops = loops) { FlakyIo(it).also { io -> flaky[0] = io } }
            return Rig(client.connection, client.conn, client.loop, flaky[0]!!, loops)
        }
    }
}
