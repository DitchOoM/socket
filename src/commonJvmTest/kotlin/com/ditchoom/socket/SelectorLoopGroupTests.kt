package com.ditchoom.socket

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The non-blocking NIO path (`useAsyncChannels = false`, `useNioBlocking = false`) runs every connection on
 * a few shared selector loops: no thread and no selector per connection, deadlines owned by the loop, a
 * closed socket failing its own waiters, and no user code on a loop thread.
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
    }

    private fun loopThreads() = Thread.getAllStackTraces().keys.count { it.name.startsWith("socket-nio") }

    /** Runs [block] with a connected pair: a listening server, the accepted side, and a client. */
    private fun withPair(block: suspend CoroutineScope.(server: ClientSocket, client: ClientSocket) -> Unit) =
        runBlocking(Dispatchers.IO) {
            withTimeout(30.seconds) {
                val listener = ServerSocket.allocate()
                val accepted = CompletableDeferred<ClientSocket>()
                val acceptor =
                    launch {
                        listener.bind(0, "127.0.0.1").collect {
                            accepted.complete(it)
                            return@collect
                        }
                    }
                // bind() returns the flow; collect it only once, in the acceptor.
                while (!listener.isListening()) delay(10)
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
        }

    /**
     * Before the shared loops every non-blocking socket opened its own selector and a thread to run it, so
     * 40 connections cost 40 threads. Now they cost a registration each on at most four.
     */
    @Test
    fun manyConnectionsShareAFewLoopThreads() =
        runBlocking(Dispatchers.IO) {
            withTimeout(60.seconds) {
                val connections = 40
                val listener = ServerSocket.allocate()
                val echoes =
                    launch {
                        listener.bind(0, "127.0.0.1").collect { peer ->
                            launch {
                                try {
                                    peer.writeString(peer.readString())
                                } finally {
                                    peer.close()
                                }
                            }
                        }
                    }
                while (!listener.isListening()) delay(10)
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

    /** The loop enforces the deadline, and a read that timed out leaves the connection readable. */
    @Test
    fun aReadDeadlineIsEnforcedByTheLoopAndLeavesTheConnectionUsable() =
        withPair { server, client ->
            val started = System.nanoTime()
            assertFailsWith<SocketTimeoutException> { client.read(200.milliseconds) }
            val waited = (System.nanoTime() - started).nanoseconds
            assertTrue(waited >= 150.milliseconds && waited < 5.seconds, "timed out after $waited")
            server.writeString("late")
            assertEquals("late", client.readString())
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
            withPair { server2, client2 ->
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
            val resumedOn = CompletableDeferred<String>()
            val reader =
                launch(Dispatchers.Unconfined) {
                    val result = client.read(10.seconds)
                    (result as? ReadResult.Data)?.buffer?.freeIfNeeded()
                    resumedOn.complete(Thread.currentThread().name)
                }
            delay(200)
            server.writeString("x")
            val thread = withTimeout(5.seconds) { resumedOn.await() }
            reader.join()
            assertTrue(!thread.startsWith("socket-nio"), "the caller's code ran on the loop thread $thread")
        }
}
