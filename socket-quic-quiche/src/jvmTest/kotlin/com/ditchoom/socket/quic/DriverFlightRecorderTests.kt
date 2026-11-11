package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The flight recorder ([DriverFlight]) names the two stalls a stalled round cannot tell apart from its
 * traffic alone. Each is planted for real, on a real loopback connection, by holding a dispatcher.
 *
 * - **A loop that was woken and never ran.** The server's driver dispatcher stops running what it is
 *   given, which is what a coroutine stranded in a scheduler queue looks like from outside. The loop
 *   cannot answer [QuicheDriver.stallSnapshot] (that is the 2026-11-08 API 35 soak failure's "no answer
 *   within 2s"), but the recording, read without the loop, says it is parked with commands queued.
 * - **A reader that was signalled and never resumed.** The client's reader runs on a dispatcher that
 *   stops running what it is given after the reader parks. The recording names the stream the loop
 *   signalled and the parked reader that never came back.
 */
class DriverFlightRecorderTests {
    private fun certPath(name: String): String =
        File(
            (this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")).toURI(),
        ).absolutePath

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private val traced =
        QuicOptions(
            alpnProtocols = listOf("flight"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            trace = QuicTraceCapture(TraceSink { }),
        )

    /** Runs what it is given on [delegate], except while [hold]ing: then it keeps it until [release]. */
    private class HoldingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        @Volatile
        private var holding = false
        private val held = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()

        fun hold() {
            holding = true
        }

        fun release() {
            holding = false
            while (true) {
                val (context, block) = held.poll() ?: break
                delegate.dispatch(context, block)
            }
        }

        val heldCount: Int get() = held.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (holding) held.add(context to block) else delegate.dispatch(context, block)
        }
    }

    @Test
    fun aLoopWokenAndNeverRunIsNamedWhileItCannotAnswerASnapshot() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(DriverFlightRecorderTests::class) {
                val serverLoop = HoldingDispatcher(Dispatchers.Default)
                val server =
                    buildJvmQuicServer(
                        QuicPortBinding.Own(port = 0, host = "127.0.0.1"),
                        tls,
                        traced,
                        QuicheDriverTuning(driverContext = serverLoop, captureFactory = { traceRecorderFor(traced) }),
                    )
                try {
                    val serverDriver = CompletableDeferred<QuicheDriver>()
                    val serverJob =
                        launch {
                            server.connections {
                                serverDriver.complete((this as QuicheBackedConnection).quicheDriver)
                                echoUntilEnd(acceptStream())
                            }
                        }
                    withTimeout(30.seconds) {
                        client(server.port) {
                            val stream = openStream()
                            stream.echo("warm")
                            val driver = serverDriver.await()

                            serverLoop.hold()
                            val round = async { stream.echo("held") }
                            // The request reaches the server and is queued for a loop that cannot run.
                            val stalled = awaitRecording(driver) { it.verdict is LoopVerdict.ResumedNeverRan }
                            assertNull(
                                withTimeoutOrNull(300.milliseconds) { driver.stallSnapshot() },
                                "the planted stall must be the one the soak saw: a loop that does not answer",
                            )
                            assertTrue(serverLoop.heldCount > 0, "the loop's wakeup is the one being held")
                            println(stalled.render(tail = 12))

                            serverLoop.release()
                            round.await()
                            val resumed = awaitRecording(driver) { it.verdict is LoopVerdict.IdleNothingQueued }
                            assertTrue(resumed.entries.any { it.event == FlightEvent.WokeByCommand }, resumed.render(12))
                            stream.shutdownSend()
                        }
                    }
                    serverJob.cancelAndJoin()
                } finally {
                    serverLoop.release()
                    server.close()
                }
            }
        }

    @Test
    fun aReaderSignalledAndNeverResumedIsNamedWithItsStream() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(DriverFlightRecorderTests::class) {
                val server =
                    buildJvmQuicServer(
                        QuicPortBinding.Own(port = 0, host = "127.0.0.1"),
                        tls,
                        traced,
                        QuicheDriverTuning(captureFactory = { traceRecorderFor(traced) }),
                    )
                try {
                    val reply = CompletableDeferred<Unit>()
                    val serverJob =
                        launch {
                            server.connections {
                                val stream = acceptStream()
                                stream.read(10.seconds) { it.remaining() }
                                reply.await()
                                stream.writeText("late")
                                awaitCancellation()
                            }
                        }
                    withTimeout(30.seconds) {
                        client(server.port) {
                            val driver = (this as QuicheBackedConnection).quicheDriver
                            val stream = openStream()
                            stream.writeText("go")
                            val readerContext = HoldingDispatcher(Dispatchers.Default)
                            val read = async(readerContext) { stream.read(10.seconds) { it.readString(it.remaining(), Charset.UTF8) } }
                            val id = stream.streamId.id
                            awaitRecording(driver) { r ->
                                r.entries.lastOrNull { it.arg == id && it.event == FlightEvent.ReaderParked } !=
                                    null
                            }

                            readerContext.hold()
                            reply.complete(Unit)
                            // Both, not the signal alone: the loop records StreamSignalled in the middle of
                            // its turn, so the first recording naming the reader can still read MidTurn (CI
                            // run 42621844610 caught it 62 us after the signal). The reader is held, so it
                            // stays signalled-not-resumed; a loop that never goes idle times this out.
                            val lost =
                                awaitRecording(driver) {
                                    id in it.signalledReadersNotResumed && it.verdict is LoopVerdict.IdleNothingQueued
                                }
                            assertEquals(listOf(id), lost.signalledReadersNotResumed, lost.render(12))
                            assertIs<LoopVerdict.IdleNothingQueued>(lost.verdict, "the loop did its part: ${lost.render(12)}")
                            println(lost.render(tail = 12))

                            readerContext.release()
                            assertEquals(ScopedRead.Data("late"), read.await())
                            val resumed = awaitRecording(driver) { it.signalledReadersNotResumed.isEmpty() }
                            assertTrue(resumed.entries.any { it.event == FlightEvent.ReaderResumed && it.arg == id }, resumed.render(12))
                        }
                    }
                    serverJob.cancelAndJoin()
                } finally {
                    server.close()
                }
            }
        }

    @Test
    fun aDriverWithoutTraceCaptureRecordsNothing() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(DriverFlightRecorderTests::class) {
                val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, traced.copy(trace = null))
                try {
                    val serverJob = launch { server.connections { echoUntilEnd(acceptStream()) } }
                    withTimeout(30.seconds) {
                        client(server.port, traced.copy(trace = null)) {
                            val stream = openStream()
                            stream.echo("untraced")
                            assertEquals(FlightRecording.Off, (this as QuicheBackedConnection).quicheDriver.flightRecording())
                            stream.shutdownSend()
                        }
                    }
                    serverJob.cancelAndJoin()
                } finally {
                    server.close()
                }
            }
        }

    private suspend fun awaitRecording(
        driver: QuicheDriver,
        within: Duration = 10.seconds,
        until: (FlightRecording.Recorded) -> Boolean,
    ): FlightRecording.Recorded {
        var last: FlightRecording.Recorded? = null
        return withTimeoutOrNull(within) {
            while (true) {
                val recording = assertIs<FlightRecording.Recorded>(driver.flightRecording())
                last = recording
                if (until(recording)) return@withTimeoutOrNull recording
                delay(5.milliseconds)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } ?: error("the recording never showed what was planted; last: ${last?.render(30)}")
    }

    private suspend fun <R> client(
        port: Int,
        options: QuicOptions = traced,
        block: suspend QuicScope.() -> R,
    ): R =
        commonJvmWithQuicConnection(
            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
            serverName = "localhost",
            quicOptions = options,
            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
            timeout = 20.seconds,
            // The test seam bypasses QuicheEngine, which is where QuicOptions.trace becomes a capture.
            tuning = QuicheDriverTuning(captureFactory = { traceRecorderFor(options) }),
            block = block,
        )

    private suspend fun echoUntilEnd(stream: QuicByteStream) {
        try {
            while (stream.read(10.seconds) { stream.writeFully(it, 5.seconds) } is ScopedRead.Data) Unit
        } finally {
            stream.close()
        }
    }

    private suspend fun QuicByteStream.echo(text: String) {
        writeText(text)
        var got = ""
        while (got.length < text.length) {
            val chunk = assertIs<ScopedRead.Data<String>>(read(10.seconds) { it.readString(it.remaining(), Charset.UTF8) })
            got += chunk.value
        }
        assertEquals(text, got)
    }

    private suspend fun QuicByteStream.writeText(text: String) {
        val out = BufferFactory.deterministic().allocate(text.length)
        try {
            out.writeString(text, Charset.UTF8)
            out.resetForRead()
            writeFully(out, 5.seconds)
        } finally {
            out.freeIfNeeded()
        }
    }
}
