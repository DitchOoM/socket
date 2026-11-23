package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Callers that are not on a main dispatcher enter the connection and send in place; the loop is woken far less. */
class CallerInPlaceTests {
    /** Loops whose sockets lend callers lanes, whatever this host's own rule. */
    private val loops = LoopSet(1, sends = SendRule.InPlace)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun callersOnDefaultSendInPlaceAndRarelyWakeTheLoop() =
        runBlocking {
            skipOnMissingNativeLib(CallerInPlaceTests::class) {
                withEchoServer { port ->
                    val recorder = arrayOfNulls<RecordingIo>(1)
                    val client = connectCore(port, loops = loops) { RecordingIo(it).also { io -> recorder[0] = io } }
                    val io = recorder[0]!!
                    val connection = client.connection
                    val before = client.loop.callerCounts.loopWakes
                    val payload = Random(11).nextBytes(ECHO_BYTES)
                    withContext(Dispatchers.Default) {
                        val stream = connection.openStream()
                        repeat(ECHOES) { assertContentEquals(payload, echoOver(stream, payload), "echo $it") }
                    }
                    val wakes = client.loop.callerCounts.loopWakes - before
                    val inPlace = client.loop.callerCounts.sendsInPlace
                    val enteredInPlace = client.conn.counts.enteredInPlace
                    val sendsByThread = io.sendsByThread()
                    val onCallers = sendsByThread.filterKeys { it.startsWith("DefaultDispatcher") }.values.sum()
                    println(
                        "[core in-place] $ECHOES echoes of $ECHO_BYTES B: caller-caused loop wakes=$wakes, in-place sends=$inPlace, " +
                            "caller entries=$enteredInPlace, left for holder=${client.conn.counts.leftForHolder}, " +
                            "handed to loop=${client.conn.counts.handedToLoop}, deferred=${client.loop.callerCounts.sendsDeferred}, " +
                            "sends by thread=$sendsByThread",
                    )
                    assertTrue(inPlace > 0 && onCallers > 0, "callers sent in place: $sendsByThread")
                    assertTrue(wakes < ECHOES / 2, "the loop was woken $wakes times by callers over $ECHOES echoes")
                    connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aDatagramThatArrivesWhileACallerIsInsideIsCopiedAndRunNotDropped() =
        runBlocking {
            skipOnMissingNativeLib(CallerInPlaceTests::class) {
                val serverMayWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
                val payload = Random(5).nextBytes(8_000)
                withEchoServer(
                    unsolicited = {
                        serverMayWrite.await()
                        openStream().writeFully(bytes(payload), 10.seconds)
                    },
                ) { port ->
                    val client = connectCore(port, loops = loops)
                    val holder = HoldWork()
                    val held = holdAsCaller(client.conn, holder)
                    try {
                        assertTrue(holder.entered.await(10, TimeUnit.SECONDS), "a caller entered the connection")
                        serverMayWrite.complete(Unit)
                        // The server's datagrams reach the loop while the caller is inside: they are copied, not dropped.
                        withTimeout(10.seconds) {
                            while (client.conn.counts.datagramsCopied == 0L) delay(10)
                        }
                    } finally {
                        holder.release.countDown()
                        held.join()
                    }
                    val received = ByteArrayOutputStream()
                    withTimeout(10.seconds) {
                        val stream = client.connection.acceptStream()
                        while (received.size() < payload.size) {
                            if (stream.read(
                                    10.seconds,
                                ) { received.write(it.readByteArray(it.remaining())) } !is ScopedReadResult.Data<*>
                            ) {
                                break
                            }
                        }
                    }
                    println(
                        "[core copy] copied=${client.conn.counts.datagramsCopied} dropped=${client.conn.counts.datagramsDropped} " +
                            "held by ${holder.heldBy}",
                    )
                    assertContentEquals(payload, received.toByteArray(), "what the server sent while the caller held the connection")
                    assertTrue(client.conn.counts.datagramsCopied >= 1L, "a datagram was copied")
                    assertEquals(0L, client.conn.counts.datagramsDropped, "no datagram was dropped")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /**
     * Makes a caller thread enter [conn] and block in [holder] until released. Work that finds the loop inside runs on
     * the loop instead; [HoldWork] declines that and the hold is tried again, so a caller always ends up holding.
     */
    private fun holdAsCaller(
        conn: Conn,
        holder: HoldWork,
    ): Thread {
        val thread =
            Thread {
                while (holder.entered.count > 0) {
                    val target = holder.ran.get() + 1
                    conn.submit(holder, CallerPlace.MayEnter)
                    while (holder.ran.get() < target) Thread.sleep(1)
                }
            }
        thread.name = "test-caller"
        thread.start()
        return thread
    }

    private fun bytes(payload: ByteArray) =
        BufferFactory.Default.allocate(payload.size).apply {
            writeBytes(payload)
            resetForRead()
        }

    private companion object {
        const val ECHOES = 300
        const val ECHO_BYTES = 1024
    }
}

/** Work that blocks the thread inside the connection until released, unless it landed on the loop, where it declines. */
private class HoldWork : ConnWork() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var heldBy = "nobody"
    val ran = AtomicInteger()

    override fun run(inside: Inside) {
        if (Thread.currentThread().name.startsWith(LOOP_THREAD)) {
            ran.incrementAndGet()
            return
        }
        ran.incrementAndGet()
        heldBy = Thread.currentThread().name
        entered.countDown()
        release.await()
    }

    override fun gone(end: ConnEnd) = Unit
}
