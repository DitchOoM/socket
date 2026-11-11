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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A socket the server accepts belongs to nobody until the bind flow's collector receives it, so the server
 * closes it on every path where the collector never does. Each test runs the collection on a [SteppedDispatcher]
 * and chooses one exact interleaving between the accept's completion and the hand-off.
 */
class LinuxAcceptHandOffTests {
    /**
     * The accept is the listener's last user when the server closes, so finishing it releases the listener on
     * another thread; the collection is cancelled while it waits for that release. The accepted socket, already
     * counted open, must be closed rather than dropped with the cancellation.
     *
     * The release runs on a dispatcher the test steps, so the collection has certainly suspended for it before the
     * cancellation. On a real thread the release can finish first, and then nothing comes back to be cancelled
     * (the next test).
     */
    @Test
    fun aSocketAcceptedAsTheServerClosesIsClosedWhenItsCollectionIsCancelledBeforeTheHandOff() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val baseline = IoUringManager.activeSockets
            val releases = SteppedDispatcher()
            val server = LinuxServerSocket(TransportConfig(), releaseContext = releases)
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
                acceptCompleted.run() // adopts the accepted socket, then suspends for the listener's release
                val release = releases.next()
                collection.cancel()
                release.run() // releases the listener and dispatches the collection's way back
                stepped.next().run()
                stepped.runQueued()
                assertTrue(collection.isCompleted, "the cancelled collection must end once its way back has run")
                assertEquals(0, delivered.value, "the collection was cancelled before the hand-off")
                client.close()
                assertEquals(
                    baseline,
                    IoUringManager.activeSockets,
                    "an accepted socket the collector never received must be closed, not left counted open",
                )
            } finally {
                collection.cancel()
                releases.runQueued()
                stepped.runQueued()
                client.close()
                server.close()
            }
        }

    /**
     * As above, but the listener's release finishes before the collection suspends for it, as it may on another
     * thread. `withContext` then returns at once and dispatches nothing, so there is no way back to cancel: the
     * collector receives the socket in the same step, and its close is the socket's close.
     */
    @Test
    fun aSocketAcceptedAsTheServerClosesReachesTheCollectorWhenTheListenersReleaseFinishesFirst() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val baseline = IoUringManager.activeSockets
            val server = LinuxServerSocket(TransportConfig(), releaseContext = RunsAtOnce)
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
                acceptCompleted.run() // adopts, releases the listener at once, hands off, and ends the collection
                stepped.runQueued()
                assertTrue(collection.isCompleted, "a listener closed during the hand-off ends the collection")
                assertEquals(1, delivered.value, "the collector receives the socket when nothing held the release")
                client.close()
                assertEquals(baseline, IoUringManager.activeSockets, "the collector's close must be the socket's close")
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
internal class SteppedDispatcher : CoroutineDispatcher() {
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

/** Runs each dispatched task at once on the dispatching thread: a release that finishes before its caller suspends. */
private object RunsAtOnce : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = block.run()
}
