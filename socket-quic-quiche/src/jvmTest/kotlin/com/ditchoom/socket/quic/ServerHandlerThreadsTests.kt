package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * **A server's handlers run on threads of the server's own**: every resume of a handler runs on one of
 * them, a handler that blocks does not hold up another connection's handler, and the threads end with
 * the server.
 */
class ServerHandlerThreadsTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 20.seconds)
    private val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun everyResumeOfAnEchoHandlerRunsOnItsServersThreads(): Unit =
        runBlocking {
            skipOnMissingNativeLib(ServerHandlerThreadsTests::class) {
                withTimeout(60.seconds) {
                    val resumes = HandlerResumes()
                    withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                        val serverThreads = "${ServerHandlerThreads.THREAD_NAME_PREFIX}$port-"
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    withContext(resumes) {
                                        val stream = acceptStream()
                                        while (true) {
                                            val echoed = stream.read(30.seconds) { chunk -> stream.write(chunk, 30.seconds) }
                                            if (echoed !is ScopedRead.Data) break
                                        }
                                        stream.close()
                                    }
                                }
                            }
                        try {
                            withQuicConnection("localhost", port, options, timeout = 20.seconds) {
                                val stream = openStream()
                                val request = BufferFactory.Default.allocate(64)

                                suspend fun roundTrip() {
                                    request.resetForWrite()
                                    repeat(64) { request.writeByte(1) }
                                    request.resetForRead()
                                    stream.write(request, 30.seconds)
                                    stream.read(30.seconds) { it.remaining() }
                                }
                                repeat(50) { roundTrip() }
                                delay(300)
                                resumes.clear()
                                val waitsBefore = ThreadWaits.of(serverThreads)
                                repeat(OPERATIONS) { roundTrip() }
                                val waits = ThreadWaits.of(serverThreads) - waitsBefore
                                val threads = resumes.byThread()
                                val elsewhere = threads.filterKeys { !it.startsWith(serverThreads) }
                                assertTrue(
                                    threads.isNotEmpty() && elsewhere.isEmpty(),
                                    "every resume of the handler must run on its server's own threads ($serverThreads…); of " +
                                        "${threads.values.sum()} resumes during $OPERATIONS echoes these ran elsewhere: $elsewhere",
                                )
                                assertTrue(
                                    threads.values.sum() <= OPERATIONS * RESUMES_PER_ECHO_AT_MOST,
                                    "$OPERATIONS echoes resumed the handler ${threads.values.sum()} times, at most " +
                                        "$RESUMES_PER_ECHO_AT_MOST per echo were expected",
                                )
                                assertTrue(
                                    waits <= OPERATIONS * RESUMES_PER_ECHO_AT_MOST,
                                    "$OPERATIONS echoes made the server's handler threads wait $waits times; a resume is one " +
                                        "hand-off to one parked thread, so at most $RESUMES_PER_ECHO_AT_MOST per echo were expected",
                                )
                                stream.shutdownSend()
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun handlersBlockedOnEveryKeptThreadDoNotDelayAnotherConnection(): Unit =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(ServerHandlerThreadsTests::class) {
                withTimeout(60.seconds) {
                    val blockers = ServerHandlerThreads.keptThreads() + 2
                    val blocked = AtomicInteger()
                    val released = AtomicInteger()
                    withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        val echoed =
                                            stream.read(30.seconds) { chunk ->
                                                if (chunk.remaining() == BLOCK_REQUEST_BYTES) {
                                                    blocked.incrementAndGet()
                                                    Thread.sleep(BLOCK_MILLIS)
                                                    released.incrementAndGet()
                                                }
                                                stream.write(chunk, 30.seconds)
                                            }
                                        if (echoed !is ScopedRead.Data) break
                                    }
                                    stream.close()
                                }
                            }
                        try {
                            val neighbourDone = CompletableDeferred<Unit>()
                            val blockerJobs =
                                List(blockers) {
                                    launch {
                                        withQuicConnection("localhost", port, options, timeout = 20.seconds) {
                                            val stream = openStream()
                                            stream.roundTrip(BLOCK_REQUEST_BYTES)
                                            neighbourDone.await()
                                            stream.shutdownSend()
                                            stream.close()
                                        }
                                    }
                                }
                            withQuicConnection("localhost", port, options, timeout = 20.seconds) {
                                val stream = openStream()
                                stream.roundTrip(64)
                                while (blocked.get() < blockers) delay(1)
                                val start = TimeSource.Monotonic.markNow()
                                repeat(NEIGHBOUR_ROUND_TRIPS) { stream.roundTrip(64) }
                                val took = start.elapsedNow()
                                assertEquals(
                                    0,
                                    released.get(),
                                    "the neighbour's round trips must be measured while every blocking handler is still blocked",
                                )
                                assertTrue(
                                    took < NEIGHBOUR_BOUND_MILLIS.milliseconds,
                                    "$NEIGHBOUR_ROUND_TRIPS round trips on a connection of its own took $took while $blockers other " +
                                        "connections' handlers were each blocked for $BLOCK_MILLIS ms; a blocked handler must cost " +
                                        "another connection no more than the start of a thread, so under $NEIGHBOUR_BOUND_MILLIS ms " +
                                        "was expected",
                                )
                                stream.shutdownSend()
                                stream.close()
                            }
                            neighbourDone.complete(Unit)
                            blockerJobs.forEach { it.join() }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun aServersHandlerThreadsEndWhenItCloses(): Unit =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(ServerHandlerThreadsTests::class) {
                withTimeout(60.seconds) {
                    var threadNamePrefix = ""
                    withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                        threadNamePrefix = "${ServerHandlerThreads.THREAD_NAME_PREFIX}$port-"
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    stream.read(30.seconds) { chunk -> stream.write(chunk, 30.seconds) }
                                    stream.close()
                                }
                            }
                        try {
                            withQuicConnection("localhost", port, options, timeout = 20.seconds) {
                                val stream = openStream()
                                stream.roundTrip(64)
                                stream.shutdownSend()
                                stream.close()
                            }
                            assertTrue(
                                liveThreads(threadNamePrefix).isNotEmpty(),
                                "an echo must have started a handler thread named $threadNamePrefix…",
                            )
                        } finally {
                            serverJob.cancel()
                        }
                    }
                    val waited = TimeSource.Monotonic.markNow()
                    while (liveThreads(threadNamePrefix).isNotEmpty() && waited.elapsedNow() < 5.seconds) delay(10)
                    assertEquals(
                        emptyList(),
                        liveThreads(threadNamePrefix),
                        "a closed server must leave none of its handler threads running",
                    )
                }
            }
        }

    private suspend fun QuicByteStream.roundTrip(bytes: Int) {
        val request = BufferFactory.Default.allocate(bytes)
        repeat(bytes) { request.writeByte(1) }
        request.resetForRead()
        write(request, 30.seconds)
        read(30.seconds) { it.remaining() }
    }

    private fun liveThreads(prefix: String): List<String> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive && it.name.startsWith(prefix) }
            .map { it.name }

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    /** How many times the threads whose name starts with a prefix have gone from running to waiting (`LockSupport.park` included). */
    private object ThreadWaits {
        private val mx = ManagementFactory.getThreadMXBean()

        fun of(namePrefix: String): Long =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name.startsWith(namePrefix) }
                .sumOf { mx.getThreadInfo(it.id)?.waitedCount?.coerceAtLeast(0) ?: 0L }
    }

    /**
     * Which thread every resume of the coroutine it is installed in ran on. kotlinx.coroutines calls
     * [updateThreadContext] on the resuming thread each time the coroutine starts or resumes, so the
     * record is complete and belongs to that coroutine alone, whatever else runs in the process.
     */
    private class HandlerResumes : ThreadContextElement<Unit> {
        private val resumesByThread = ConcurrentHashMap<String, AtomicInteger>()

        override val key: CoroutineContext.Key<*> get() = Key

        override fun updateThreadContext(context: CoroutineContext) {
            resumesByThread.computeIfAbsent(Thread.currentThread().name.substringBefore(" @")) { AtomicInteger() }.incrementAndGet()
        }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: Unit,
        ) = Unit

        fun clear() = resumesByThread.clear()

        fun byThread(): Map<String, Int> = resumesByThread.mapValues { it.value.get() }

        private object Key : CoroutineContext.Key<HandlerResumes>
    }

    private companion object {
        const val OPERATIONS = 500
        const val RESUMES_PER_ECHO_AT_MOST = 3
        const val BLOCK_REQUEST_BYTES = 1
        const val BLOCK_MILLIS = 2_000L
        const val NEIGHBOUR_ROUND_TRIPS = 20
        const val NEIGHBOUR_BOUND_MILLIS = 500L
    }
}
