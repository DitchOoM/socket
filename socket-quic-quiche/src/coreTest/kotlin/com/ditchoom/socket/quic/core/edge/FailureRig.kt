@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.testing.currentThreadName
import com.ditchoom.socket.quic.core.testing.runOnANewThread
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndDecrement
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A core client on a loop of its own against a core echo server, so a failure planted in its loop ends no other test's. */
internal class FailureRig(
    val connection: CoreConnection,
    val conn: Conn,
    val loop: Loop,
    val io: RefusingIo,
    private val loops: LoopSet,
) {
    /** How the connection ended, waiting up to ten seconds for it. */
    suspend fun ended(): ConnEnd {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < 10.seconds) {
            when (val end = connection.end()) {
                is EdgeEnd.Ended -> return end.end
                is EdgeEnd.Open -> delay(5)
            }
        }
        throw AssertionError("the connection did not end")
    }

    /** The loop's pass count as the loop thread sees it. */
    suspend fun runOnLoop(): Long {
        val work = PassesWork(loop)
        loop.port.submit(work)
        return withTimeout(5.seconds) { work.passes.await() }
    }

    fun stop() = loops.close()

    companion object {
        suspend fun start(port: Int): FailureRig {
            val loops = LoopSet(1)
            val wrapped = ArrayList<RefusingIo>(1)
            val client = connectCore(port, loops = loops) { RefusingIo(it).also { io -> wrapped.add(io) } }
            return FailureRig(client.connection, client.conn, client.loop, wrapped[0], loops)
        }
    }
}

/** Runs [body] against a fresh rig and core echo server, bounded by 30 seconds. */
internal fun withFailureRig(body: suspend CoroutineScope.(FailureRig) -> Unit) =
    runBlocking {
        withCoreEchoServer { server ->
            withContext(Dispatchers.IO) {
                val rig = FailureRig.start(server.port)
                try {
                    withTimeout(30.seconds) { body(rig) }
                } finally {
                    rig.stop()
                }
            }
        }
    }

/** The real socket's io, refusing the next sends a thread other than the loop makes when told to. */
internal class RefusingIo(
    private val real: DatagramBatchIo,
) : DatagramBatchIo by real {
    private val refusals = AtomicInt(0)

    /** The next [count] sends from a thread other than the loop's are refused as a full socket, taking nothing. */
    fun refuseCallerSends(count: Int) = refusals.store(count)

    override fun send(batch: SendBatch): SendBatchResult =
        if (!onTheLoop() && refusals.fetchAndDecrement() > 0) SendBatchResult.Partial(0, UdpError.WouldBlock) else real.send(batch)
}

internal fun onTheLoop(): Boolean = currentThreadName().startsWith(LOOP_THREAD)

/** Answers every allocation by throwing what it was given: a read's attempt throws inside the connection. */
internal class ThrowingFactory(
    private val planted: Exception,
) : BufferFactory by BufferFactory.Default {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer = throw planted
}

internal class ThrowingLoopWork(
    private val planted: Exception,
) : LoopWork() {
    override fun run(port: LoopPort) = throw planted

    override fun gone(end: ConnEnd) = Unit
}

/** Answers on the loop thread with the number of passes the loop has made. */
private class PassesWork(
    private val loop: Loop,
) : LoopWork() {
    val passes = CompletableDeferred<Long>()

    override fun run(port: LoopPort) {
        passes.complete(loop.counts.passes)
    }

    override fun gone(end: ConnEnd) {
        passes.completeExceptionally(IllegalStateException("the loop had stopped"))
    }
}

internal class ThrowingWork(
    private val planted: Throwable,
) : ConnWork() {
    override fun run(inside: Inside) = throw planted

    override fun gone(end: ConnEnd) = Unit
}

/** Holds the connection from inside until released, then throws what it is given. */
internal class HoldThenThrow(
    private val planted: Exception,
) : ConnWork() {
    val inside = CompletableDeferred<Unit>()
    private val released = AtomicInt(0)

    fun release() = released.store(1)

    override fun run(inside: Inside) {
        this.inside.complete(Unit)
        while (released.load() == 0) Unit
        throw planted
    }

    override fun gone(end: ConnEnd) {
        inside.complete(Unit)
    }
}

/** Throws what it is given when a caller runs it, and does nothing when the loop does. */
internal class OnACaller(
    private val planted: Throwable,
) : ConnWork() {
    val settled = CompletableDeferred<Unit>()

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
internal class SendOnACaller(
    private val stream: QuicStreamId,
    private val bytes: ReadBuffer,
) : ConnWork() {
    val settled = CompletableDeferred<Unit>()

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

internal class LateWork : ConnWork() {
    val gone = CompletableDeferred<Int>()

    override fun run(inside: Inside) {
        gone.completeExceptionally(IllegalStateException("ran on a failed connection"))
    }

    override fun gone(end: ConnEnd) {
        gone.complete(1)
    }
}

/** Sends [bytes] on [stream] from a caller thread, not the loop: work that finds the loop inside runs there and is tried again. */
internal suspend fun sendOnACaller(
    rig: FailureRig,
    stream: QuicStreamId,
    bytes: ReadBuffer,
) {
    repeat(1_000) {
        val work = SendOnACaller(stream, bytes)
        runOnANewThread("core-test-caller") { rig.conn.submit(work, CallerPlace.MayEnter) }
        withTimeout(10.seconds) { work.settled.await() }
        if (work.sent) return
    }
    throw AssertionError("no caller ever sent")
}

/**
 * Submits the work [make] builds from a caller thread until a caller, not the loop, ran it: work that finds the loop
 * inside runs on the loop, where [OnACaller] declines.
 */
internal suspend fun runOnACaller(
    rig: FailureRig,
    make: () -> OnACaller,
) {
    repeat(100) {
        val work = make()
        runOnANewThread("core-test-caller") { rig.conn.submit(work, CallerPlace.MayEnter) }
        withTimeout(10.seconds) { work.settled.await() }
        if (work.ranOnACaller) return
    }
    throw AssertionError("the work never ran on a caller")
}
