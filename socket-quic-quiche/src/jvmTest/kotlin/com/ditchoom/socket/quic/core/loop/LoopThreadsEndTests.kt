package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A process that closed every core connection, its server and its set of client loops is left with none of their threads:
 * the loops, and the threads that ran the application's handlers. A thread that outlived them would be a leak per test in
 * this suite and a permanent cost in an application that used QUIC once.
 *
 * The threads are told apart by identity, so whatever other tests keep on the shared set cannot mask or fake a result.
 * Two rounds: the second proves a set whose threads all ended places connections again.
 */
class LoopThreadsEndTests {
    @Test
    fun everyThreadOfTheServerAndOfTheClientLoopsEndsOnceTheyAreClosed() =
        runBlocking {
            skipOnMissingNativeLib(LoopThreadsEndTests::class) {
                repeat(2) { round ->
                    val before = coreThreads()
                    val loops = LoopSet(LOOPS)
                    try {
                        withCoreEchoServer { server ->
                            val clients = withContext(Dispatchers.IO) { List(CONNECTIONS) { connectCore(server.port, loops = loops) } }
                            clients
                                .map { client -> async { echoOver(client.connection.openStream(), ByteArray(PAYLOAD) { it.toByte() }) } }
                                .awaitAll()
                                .forEach { assertContentEquals(ByteArray(PAYLOAD) { it.toByte() }, it) }
                            val running = coreThreads() - before
                            assertTrue(running.isNotEmpty(), "round $round: no thread of the core ran, so nothing was checked")
                            clients.forEach { it.connection.close(QuicError.NoError) }
                        }
                    } finally {
                        loops.close()
                    }
                    awaitNone(before, "round $round")
                }
            }
        }

    /** The core's threads alive now: its loops and its handler pools. */
    private fun coreThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(PREFIX) }.toSet()

    private fun awaitNone(
        before: Set<Thread>,
        label: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if ((coreThreads() - before).isEmpty()) return
            Thread.sleep(20)
        }
        val alive = coreThreads() - before
        throw AssertionError(
            "$label: ${alive.size} core thread(s) still alive 10 s after every connection, the server and the set closed:\n" +
                alive.joinToString("\n") { t -> "${t.name}\n    at " + t.stackTrace.joinToString("\n    at ") },
        )
    }

    private companion object {
        const val PREFIX = "quic-core-"
        const val LOOPS = 2
        const val CONNECTIONS = 6
        const val PAYLOAD = 200
    }
}
