package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.nio.BaseClientSocket
import com.ditchoom.socket.nio.NioServerSocket
import com.ditchoom.socket.nio.util.SelectorLoop
import com.ditchoom.socket.nio.util.SelectorLoopGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The non-blocking NIO path (`useAsyncChannels = false`, `useNioBlocking = false`, and a listener built with
 * `NioServerSocket(nonBlocking = true)`) runs every connection on a few shared selector loops: no thread and
 * no selector per connection, deadlines owned by the loop, a closed socket failing its own waiters, and no
 * user code on a loop thread.
 */
class SelectorLoopGroupTests {
    private var savedAsync = true
    private var savedBlocking = false

    @BeforeTest
    fun useTheSelectorLoops() {
        savedAsync = useAsyncChannels
        savedBlocking = useNioBlocking
        useAsyncChannels = false
        useNioBlocking = false
    }

    @AfterTest
    fun restore() {
        useAsyncChannels = savedAsync
        useNioBlocking = savedBlocking
        SelectorLoop.threadStarter = { it.start() }
    }

    private fun loopThreads() = Thread.getAllStackTraces().keys.count { it.name.startsWith("socket-nio") }

    private fun listener() = NioServerSocket(TransportConfig(), nonBlocking = true)

    private fun loopOf(socket: ClientSocket) = (socket as BaseClientSocket).loop!!

    /** Runs [block] with a connected pair: the accepted side and a client. */
    private suspend fun usingPair(block: suspend CoroutineScope.(server: ClientSocket, client: ClientSocket) -> Unit) =
        coroutineScope {
            val listener = listener()
            val accepted = CompletableDeferred<ClientSocket>()
            val flow = listener.bind(0, "127.0.0.1")
            val acceptor = launch { flow.collect { accepted.complete(it) } }
            val client = ClientSocket.connect(listener.port(), "127.0.0.1")
            val server = accepted.await()
            try {
                block(server, client)
            } finally {
                client.close()
                server.close()
                listener.close()
                acceptor.cancel()
            }
        }

    private fun withPair(block: suspend CoroutineScope.(server: ClientSocket, client: ClientSocket) -> Unit) =
        runBlocking(Dispatchers.IO) { withTimeout(30.seconds) { usingPair(block) } }

    /**
     * Before the shared loops every non-blocking socket opened its own selector and a thread to run it, so
     * 40 connections cost 40 threads. Now they cost a registration each on at most four.
     */
    @Test
    fun manyConnectionsShareAFewLoopThreads() =
        runBlocking(Dispatchers.IO) {
            withTimeout(60.seconds) {
                val connections = 40
                val listener = listener()
                val flow = listener.bind(0, "127.0.0.1")
                val echoes =
                    launch {
                        flow.collect { peer ->
                            launch {
                                try {
                                    peer.writeString(peer.readString())
                                } finally {
                                    peer.close()
                                }
                            }
                        }
                    }
                val clients = List(connections) { ClientSocket.connect(listener.port(), "127.0.0.1") }
                try {
                    clients
                        .mapIndexed { i, c ->
                            async {
                                c.writeString("ping-$i")
                                assertEquals("ping-$i", c.readString())
                            }
                        }.awaitAll()
                    val threads = loopThreads()
                    assertTrue(threads in 1..4, "$connections connections ran on $threads loop threads, expected 1..4")
                } finally {
                    clients.forEach { it.close() }
                    listener.close()
                    echoes.cancel()
                }
            }
        }

    /** The loop enforces the deadline, and a read that timed out leaves the connection readable (and a new operation works). */
    @Test
    fun aReadDeadlineIsEnforcedByTheLoopAndLeavesTheConnectionUsable() =
        withPair { server, client ->
            val started = System.nanoTime()
            assertFailsWith<SocketTimeoutException> { client.read(200.milliseconds) }
            val waited = (System.nanoTime() - started).nanoseconds
            assertTrue(waited >= 150.milliseconds && waited < 5.seconds, "timed out after $waited")
            server.writeString("late")
            assertEquals("late", client.readString())
            // And a fresh round trip on the same socket after the timeout.
            client.writeString("again")
            assertEquals("again", server.readString())
        }

    /** A read parked with no deadline ends, typed, when its own socket closes: the shared selector stays open. */
    @Test
    fun closingASocketFailsItsPendingRead() =
        withPair { _, client ->
            val pending = async(Dispatchers.IO) { runCatching { client.read(Duration.INFINITE) } }
            delay(200)
            client.close()
            val ended = withTimeout(5.seconds) { pending.await() }.exceptionOrNull()
            assertTrue(ended is SocketClosedException, "the read ended as $ended")
        }

    /** One connection closing does not disturb another that shares its loop. */
    @Test
    fun aClosedSocketLeavesItsNeighbourOnTheLoopWorking() =
        withPair { server, client ->
            usingPair { server2, client2 ->
                client2.close()
                server2.close()
                server.writeString("still here")
                assertEquals("still here", client.readString())
            }
        }

    /** A continuation with no dispatcher to leave through must not run on the loop thread. */
    @Test
    fun aCallerWithNoDispatcherIsNotResumedOnALoopThread() =
        withPair { server, client ->
            val thread = resumedOnWhenReadUnder(Dispatchers.Unconfined, server, client)
            assertTrue(!thread.startsWith("socket-nio"), "the caller's code ran on the loop thread $thread")
        }

    /** The same for a dispatcher that says it needs no dispatch (an `UnconfinedTestDispatcher` does): not only an identity check. */
    @Test
    fun aDispatcherThatNeverDispatchesIsNotResumedOnALoopThread() =
        withPair { server, client ->
            val thread = resumedOnWhenReadUnder(InlineDispatcher, server, client)
            assertTrue(!thread.startsWith("socket-nio"), "the caller's code ran on the loop thread $thread")
        }

    /** An interceptor that is not a dispatcher at all is unknown, so it is treated as inline. */
    @Test
    fun aCustomInterceptorIsNotResumedOnALoopThread() =
        withPair { server, client ->
            val thread = resumedOnWhenReadUnder(PassThroughInterceptor, server, client)
            assertTrue(!thread.startsWith("socket-nio"), "the caller's code ran on the loop thread $thread")
        }

    private suspend fun CoroutineScope.resumedOnWhenReadUnder(
        context: CoroutineContext,
        server: ClientSocket,
        client: ClientSocket,
    ): String {
        val resumedOn = CompletableDeferred<String>()
        val reader =
            launch(context) {
                val result = client.read(10.seconds)
                (result as? ReadResult.Data)?.buffer?.freeIfNeeded()
                resumedOn.complete(Thread.currentThread().name)
            }
        delay(200)
        server.writeString("x")
        val thread = withTimeout(5.seconds) { resumedOn.await() }
        reader.join()
        return thread
    }

    /** A dispatcher that throws when asked to dispatch (a closed single-thread context) must not take the loop, or its neighbours, with it. */
    @Test
    fun aDispatcherThatThrowsDoesNotFailOtherSockets() =
        withPair { server, client ->
            usingPair { server2, client2 ->
                val dispatcher = ClosingDispatcher()
                // Its own scope: a coroutine whose dispatcher refuses its resume can never finish, so nothing may wait for it.
                CoroutineScope(dispatcher).launch {
                    runCatching { client.read(10.seconds) }
                }
                delay(200)
                // Now the caller's dispatcher closes under the parked read, as a closed single-thread context does.
                dispatcher.closed = true
                server.writeString("to the broken one")
                delay(300)
                // The loop is alive and another socket on the same loops is served.
                server2.writeString("hello")
                assertEquals("hello", client2.readString())
            }
        }

    /** A loop whose thread cannot start is not left reachable: the caller gets a typed error and a retry works. */
    @Test
    fun aLoopThatFailsToStartIsNotLeftBehind() {
        val selector = Selector.open()
        try {
            SelectorLoop.threadStarter = { throw OutOfMemoryError("unable to create native thread") }
            val failure = assertFailsWith<SocketIOException> { SelectorLoop.of(selector) }
            assertTrue("Could not start" in failure.message, failure.message)
            SelectorLoop.threadStarter = { it.start() }
            assertTrue(SelectorLoop.of(selector).isRunning)
        } finally {
            selector.close()
        }
    }

    @Test
    fun theLoopCountIsClampedToASaneRange() {
        assertEquals(1, SelectorLoopGroup.loopCount(0, 8))
        assertEquals(1, SelectorLoopGroup.loopCount(-3, 8))
        assertEquals(16, SelectorLoopGroup.loopCount(1000, 8))
        assertEquals(4, SelectorLoopGroup.loopCount(null, 64))
        assertEquals(1, SelectorLoopGroup.loopCount(null, 1))
        assertEquals(3, SelectorLoopGroup.loopCount(3, 64))
    }

    /** A read and a write waiting on one socket are cancelled independently: each leaves the other waiting and nothing dead behind. */
    @Test
    fun cancellingAReadAndAWriteIndependentlyLeavesNoWaiterBehind() =
        withPair { server, client ->
            val loop = loopOf(client)
            val reader = launch(Dispatchers.IO) { runCatching { client.read(Duration.INFINITE) } }
            val chunk = BufferFactory.Default.allocate(1 shl 20)
            val writer =
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            chunk.position(0)
                            chunk.setLimit(chunk.capacity)
                            client.writeFully(chunk, Duration.INFINITE)
                        }
                    } catch (_: Exception) {
                        // cancelled, or the connection closed under it
                    }
                }
            // The peer reads nothing, so the send buffer fills and the writer waits for OP_WRITE.
            awaitUntil { loop.parkedChannelCount == 1 && writer.isActive && reader.isActive }
            writer.cancelAndJoin()
            assertTrue(reader.isActive, "cancelling the writer must not end the reader")
            // The reader still waits and is still woken by the peer.
            server.writeString("x")
            withTimeout(5.seconds) { reader.join() }
            awaitUntil { loop.parkedChannelCount == 0 }
            // Cancel the other way round: a reader cancelled while a later write completes does not hit a dead continuation.
            val second = launch(Dispatchers.IO) { runCatching { client.read(Duration.INFINITE) } }
            awaitUntil { loop.parkedChannelCount == 1 }
            second.cancelAndJoin()
            awaitUntil { loop.parkedChannelCount == 0 }
            server.writeString("y")
            assertEquals("y", client.readString())
        }

    /** Closing a socket under both a waiting reader and a waiting writer fails each of them exactly once, typed. */
    @Test
    fun closingUnderAReaderAndAWriterFailsEachOnce() =
        withPair { _, client ->
            val loop = loopOf(client)
            val readerOutcomes =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val writerOutcomes =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val reader =
                async(Dispatchers.IO) {
                    runCatching { client.read(Duration.INFINITE) }.also { readerOutcomes.incrementAndGet() }
                }
            val chunk = BufferFactory.Default.allocate(1 shl 20)
            val writer =
                async(Dispatchers.IO) {
                    runCatching {
                        while (true) {
                            chunk.position(0)
                            chunk.setLimit(chunk.capacity)
                            client.writeFully(chunk, Duration.INFINITE)
                        }
                    }.also { writerOutcomes.incrementAndGet() }
                }
            awaitUntil { loop.parkedChannelCount == 1 && reader.isActive && writer.isActive }
            delay(300)
            client.close()
            val read = withTimeout(5.seconds) { reader.await() }.exceptionOrNull()
            val write = withTimeout(5.seconds) { writer.await() }.exceptionOrNull()
            assertIs<SocketClosedException>(read, "the read ended as $read")
            assertTrue(write is SocketClosedException || write is SocketException, "the write ended as $write")
            delay(200)
            assertEquals(1, readerOutcomes.get())
            assertEquals(1, writerOutcomes.get())
        }

    /** After many open/close cycles the shared loops hold no parked entry and no key for the closed sockets. */
    @Test
    fun theLoopsReturnToNothingParkedAfterManyOpenCloseCycles() =
        runBlocking(Dispatchers.IO) {
            withTimeout(120.seconds) {
                val before = SelectorLoopGroup.running().sumOf { it.registeredKeyCount }
                repeat(60) {
                    val listener = listener()
                    val flow = listener.bind(0, "127.0.0.1")
                    val accepted = CompletableDeferred<ClientSocket>()
                    val acceptor = launch { flow.collect { accepted.complete(it) } }
                    val client = ClientSocket.connect(listener.port(), "127.0.0.1")
                    val server = accepted.await()
                    client.writeString("a")
                    assertEquals("a", server.readString())
                    // Leave a read waiting on each side when the sockets close, half of the time.
                    val pendingClient = if (it % 2 == 0) async { runCatching { client.read(Duration.INFINITE) } } else null
                    delay(5)
                    client.close()
                    server.close()
                    listener.close()
                    acceptor.cancel()
                    pendingClient?.await()
                }
                awaitUntil(10.seconds) {
                    SelectorLoopGroup.running().all { it.parkedChannelCount == 0 } &&
                        SelectorLoopGroup.running().sumOf { it.registeredKeyCount } <= before
                }
            }
        }

    /**
     * Commands posted from many threads against a loop that is about to sleep: every wait is woken within a bound
     * (a lost wake-up would hang one of them, and this fails on the timeout).
     */
    @Test
    fun manyConcurrentWaitsAreAllWokenWithinABound() =
        runBlocking<Unit>(Dispatchers.IO) {
            withTimeout(90.seconds) {
                val listener = listener()
                val flow = listener.bind(0, "127.0.0.1")
                val echoes =
                    launch {
                        flow.collect { peer ->
                            launch {
                                try {
                                    while (true) {
                                        val r = peer.read(Duration.INFINITE)
                                        if (r !is ReadResult.Data) break
                                        peer.writeFully(r.buffer, Duration.INFINITE)
                                        r.buffer.freeIfNeeded()
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                val clients = List(32) { ClientSocket.connect(listener.port(), "127.0.0.1") }
                try {
                    clients
                        .map { c ->
                            async(Dispatchers.Default) {
                                repeat(100) { i ->
                                    c.writeString("m$i")
                                    assertEquals("m$i", c.readString(deadline = 5.seconds))
                                }
                            }
                        }.awaitAll()
                } finally {
                    clients.forEach { it.close() }
                    listener.close()
                    echoes.cancel()
                }
            }
        }

    // The loop's own API, on a selector of the test's own and two raw loopback channels.

    /** Readiness wins a tie with the deadline: data already queued and a deadline already past is readiness. */
    @Test
    fun aWaitWhoseDeadlineHasPassedButWhoseChannelIsReadyIsReady() =
        withRawPair { quiet, _, feed ->
            val selector = Selector.open()
            try {
                feed()
                Thread.sleep(50)
                val loop = SelectorLoop.of(selector)
                runBlocking { loop.awaitReady(quiet, SelectionKey.OP_READ, 1.nanoseconds) }
            } finally {
                selector.close()
            }
        }

    /** Two waits with equal deadlines are both timed out, each once, and neither is lost to the ordered set. */
    @Test
    fun twoWaitsWithTheSameDeadlineBothTimeOut() =
        withRawPair { quiet, busy, _ ->
            val selector = Selector.open()
            try {
                val loop = SelectorLoop.of(selector)
                val outcomes =
                    runBlocking {
                        listOf(quiet, busy)
                            .map { channel ->
                                async(Dispatchers.IO) { runCatching { loop.awaitReady(channel, SelectionKey.OP_READ, 150.milliseconds) } }
                            }.awaitAll()
                    }
                outcomes.forEach { assertIs<SocketTimeoutException>(it.exceptionOrNull(), "ended as $it") }
            } finally {
                selector.close()
            }
        }

    /** A timeout near the largest duration must not overflow into an immediate timeout. */
    @Test
    fun aHugeFiniteTimeoutDoesNotTimeOutAtOnce() =
        withRawPair { quiet, _, _ ->
            val selector = Selector.open()
            try {
                val loop = SelectorLoop.of(selector)
                val waiting =
                    CoroutineScope(Dispatchers.IO).async {
                        runCatching { loop.awaitReady(quiet, SelectionKey.OP_READ, Duration.parse("100000d")) }
                    }
                Thread.sleep(300)
                if (!waiting.isActive) kotlin.test.fail("a 100000-day wait ended after 300 ms")
                waiting.cancel()
            } finally {
                selector.close()
            }
        }

    private fun withRawPair(block: (quiet: SocketChannel, busy: SocketChannel, feed: () -> Unit) -> Unit) {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            SocketChannel.open(server.localAddress).use { quiet ->
                val peerOfQuiet = server.accept()
                SocketChannel.open(server.localAddress).use { busy ->
                    server.accept().use {
                        peerOfQuiet.use {
                            quiet.configureBlocking(false)
                            busy.configureBlocking(false)
                            block(quiet, busy) { peerOfQuiet.write(java.nio.ByteBuffer.wrap(byteArrayOf(1))) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun awaitUntil(
        bound: Duration = 5.seconds,
        condition: () -> Boolean,
    ) {
        withTimeout(bound) { while (!condition()) delay(10) }
    }

    private suspend fun Job.cancelAndJoin() {
        cancel()
        join()
    }
}

/** A dispatcher that never dispatches: every resume runs on the thread that resumes. */
private object InlineDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext) = false

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = block.run()
}

/** A dispatcher that works until [closed] is set, then fails to dispatch, as a closed single-thread executor does. */
private class ClosingDispatcher : CoroutineDispatcher() {
    @Volatile var closed = false

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (closed) throw java.util.concurrent.RejectedExecutionException("closed")
        Dispatchers.IO.dispatch(context, block)
    }
}

/** An interceptor that is not a dispatcher: it only passes the continuation through. */
private object PassThroughInterceptor :
    kotlin.coroutines.AbstractCoroutineContextElement(kotlin.coroutines.ContinuationInterceptor),
    kotlin.coroutines.ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: kotlin.coroutines.Continuation<T>) = continuation
}
