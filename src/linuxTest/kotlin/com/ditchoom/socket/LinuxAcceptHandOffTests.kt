package com.ditchoom.socket

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A socket the server accepts belongs to nobody until the bind flow's collector receives it, so the server
 * closes it on every path where the collector never does. Each test runs the collection on a [SteppedDispatcher]
 * and cancels it at one exact point between the accept's completion and the hand-off.
 */
class LinuxAcceptHandOffTests {
    /**
     * The accept is the listener's last user when the server closes, so finishing it releases the listener on
     * another thread; the collection is cancelled before it comes back. The accepted socket, already counted
     * open, must be closed rather than dropped with the cancellation.
     */
    @Test
    fun aSocketAcceptedAsTheServerClosesIsClosedWhenItsCollectionIsCancelledBeforeTheHandOff() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val baseline = IoUringManager.activeSockets
            val server = ServerSocket.allocate()
            val accepted = server.bind()
            val stepped = SteppedDispatcher()
            val delivered = AtomicInt(0)
            val collection =
                CoroutineScope(stepped).launch {
                    accepted.collect {
                        delivered.incrementAndGet()
                        it.close()
                    }
                }
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            try {
                stepped.next().run() // the collection starts and parks in the accept
                client.open(server.port(), "127.0.0.1")
                val acceptCompleted = stepped.next()
                server.close() // the parked accept is now the listener's last user
                acceptCompleted.run() // adopts the accepted socket, then releases the listener off this thread
                val backFromRelease = stepped.next()
                collection.cancel()
                backFromRelease.run()
                stepped.runQueued()
                client.close()
                assertEquals(
                    baseline,
                    IoUringManager.activeSockets,
                    "an accepted socket the collector never received (delivered=${delivered.value}) must be " +
                        "closed, not left counted open",
                )
            } finally {
                collection.cancel()
                stepped.runQueued()
                client.close()
                server.close()
            }
        }

    /**
     * The accept completes with a descriptor, and the collection is cancelled before the accept resumes, so the
     * accept's caller sees only its cancellation. The descriptor must be closed: left open, the client's read
     * never ends, because nothing will ever close the server side of its connection.
     */
    @Test
    fun aDescriptorAcceptedForACancelledCollectionIsClosed() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val server = ServerSocket.allocate()
            val accepted = server.bind()
            val stepped = SteppedDispatcher()
            val delivered = AtomicInt(0)
            val collection =
                CoroutineScope(stepped).launch {
                    accepted.collect {
                        delivered.incrementAndGet()
                        it.close()
                    }
                }
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            try {
                stepped.next().run() // the collection starts and parks in the accept
                client.open(server.port(), "127.0.0.1")
                val acceptCompleted = stepped.next()
                collection.cancel()
                acceptCompleted.run()
                stepped.runQueued()
                val read = runCatching { client.read(2.seconds) }
                val result = read.getOrElse { fail("delivered=${delivered.value}: the client's read did not end: $it") }
                if (result is ReadResult.Data) result.buffer.freeIfNeeded()
                assertEquals(
                    ReadResult.End,
                    result,
                    "delivered=${delivered.value}: the server side of a connection accepted for a cancelled " +
                        "collection must be closed",
                )
            } finally {
                collection.cancel()
                stepped.runQueued()
                client.close()
                server.close()
            }
        }
}

/** A dispatcher that runs nothing until the test takes each dispatched task, in order, and runs it itself. */
private class SteppedDispatcher : CoroutineDispatcher() {
    private val tasks = Channel<Runnable>(Channel.UNLIMITED)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.trySend(block)
    }

    /** The next dispatched task, waiting for one to be dispatched. */
    suspend fun next(): Runnable = withTimeout(10.seconds) { tasks.receive() }

    /** Runs every task already dispatched, and those they dispatch here, until none is queued. */
    fun runQueued() {
        while (true) {
            val task = tasks.tryReceive().getOrNull() ?: return
            task.run()
        }
    }
}
