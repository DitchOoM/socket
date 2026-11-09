@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.udp.InternalDatagramLoopApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **The QUIC event loops never run the application's code.**
 *
 * A loop is one thread serving many connections; code that blocks it stalls every one of them (the
 * owned-loop spike, which ran server handlers on the loop, measured a 64-byte round trip at 7.8 ms beside
 * a handler that blocked for 5 ms). So a handler, a client block, a coroutine launched in a connection's
 * scope, and every continuation after a read, write, open or accept must resume on the application's
 * dispatcher, never on a `quic-loop-*` thread.
 *
 * A trace sink ([QuicTraceCapture]) is application code too: the events a connection records on its
 * loop are handed to the sink off the loop ([com.ditchoom.socket.quic.trace.OffLoopTraceSink]).
 *
 * Every point where application code runs records its thread; any that is a loop thread fails the test.
 * The control proves the probe can see a loop thread at all: a coroutine that opts into
 * `Dispatchers.Unconfined` resumes on whatever thread completes its wait, which for a stream read is the
 * loop, and the probe must catch that.
 */
class ApplicationCodeNeverRunsOnALoopTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("app-code"), verifyPeer = false, idleTimeout = 1.minutes, migration = MigrationPolicy.Forbidden)

    /** [options] with a trace capture whose sink (application code too) records where it runs. */
    private fun tracedOptions(probe: Probe): QuicOptions =
        QuicOptions(
            alpnProtocols = listOf("app-code"),
            verifyPeer = false,
            idleTimeout = 1.minutes,
            migration = MigrationPolicy.Forbidden,
            trace =
                QuicTraceCapture(
                    TraceSink {
                        probe.traced.incrementAndGet()
                        probe.note("trace sink")
                    },
                ),
        )

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private class Probe {
        val onLoop = ConcurrentLinkedQueue<String>()
        val checked = AtomicInteger()
        val traced = AtomicInteger()

        fun note(where: String) {
            checked.incrementAndGet()
            val thread = Thread.currentThread()
            if (thread.name.startsWith(LOOP_THREAD_PREFIX)) onLoop.add("$where ran on ${thread.name}")
        }
    }

    @Test
    fun handlersBlocksScopesAndContinuationsRunOnTheApplicationsDispatcher() =
        failIfWedged(WATCHDOG, "application code vs the loops", Dispatchers.IO) {
            skipOnMissingNativeLib(ApplicationCodeNeverRunsOnALoopTests::class) {
                val probe = Probe()
                val loopsUsed = CompletableDeferred<Int>()
                val options = tracedOptions(probe)
                withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tlsConfig, quicOptions = options) {
                    val serverJob =
                        launch {
                            connections {
                                probe.note("server handler start")
                                launch { probe.note("server connection-scope launch") }
                                (this as QuicConnection).state.first { it is QuicConnectionState.Established }
                                probe.note("server after state collect")
                                val stream = acceptStream()
                                probe.note("server after acceptStream")
                                while (true) {
                                    val r =
                                        stream.read(OP_DEADLINE) { bytes ->
                                            probe.note("server read scope")
                                            stream.writeFully(bytes, OP_DEADLINE)
                                            probe.note("server after write")
                                        }
                                    probe.note("server after read")
                                    if (r !is ScopedRead.Data) break
                                }
                                stream.close()
                                probe.note("server after close")
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, options, timeout = OP_DEADLINE) {
                            probe.note("client block start")
                            launch { probe.note("client connection-scope launch") }.join()
                            val stream = openStream()
                            probe.note("client after openStream")
                            val out = bufferFactory.allocate(16)
                            repeat(ECHOES) { i ->
                                out.resetForWrite()
                                out.writeString("echo-$i", Charset.UTF8)
                                out.resetForRead()
                                stream.writeFully(out, OP_DEADLINE)
                                probe.note("client after write")
                                stream.read(OP_DEADLINE) { probe.note("client read scope") }
                                probe.note("client after read")
                            }
                            loopsUsed.complete(quicLoopGroup.runningThreads().size)
                            stream.shutdownSend()
                            stream.close()
                            probe.note("client after close")
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
                assertTrue(loopsUsed.await() > 0, "no QUIC loop thread was running, so nothing here was checked against one")
                assertTrue(probe.checked.get() > ECHOES * 4, "the probe recorded only ${probe.checked.get()} points")
                assertTrue(probe.traced.get() > 0, "no trace event reached a sink, so the sinks were not checked")
                if (probe.onLoop.isNotEmpty()) {
                    fail(
                        "application code ran on a QUIC loop thread:\n" + probe.onLoop.distinct().joinToString("\n"),
                    )
                }
            }
        }

    /** The probe's sensitivity: a coroutine that asks to be resumed where its wait completes is resumed on a loop. */
    @Test
    fun theControlAnUnconfinedReaderIsResumedOnTheLoop() =
        failIfWedged(WATCHDOG, "the control", Dispatchers.IO) {
            skipOnMissingNativeLib(ApplicationCodeNeverRunsOnALoopTests::class) {
                val probe = Probe()
                withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tlsConfig, quicOptions = options) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (stream.read(OP_DEADLINE) { stream.writeFully(it, OP_DEADLINE) } is ScopedRead.Data) Unit
                                stream.close()
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, options, timeout = OP_DEADLINE) {
                            val stream = openStream()
                            val out = bufferFactory.allocate(16)
                            repeat(ECHOES) { i ->
                                out.resetForWrite()
                                out.writeString("echo-$i", Charset.UTF8)
                                out.resetForRead()
                                withContext(Dispatchers.Unconfined) {
                                    stream.writeFully(out, OP_DEADLINE)
                                    stream.read(OP_DEADLINE) { }
                                    probe.note("unconfined after read")
                                }
                            }
                            stream.shutdownSend()
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
                assertTrue(
                    probe.onLoop.isNotEmpty(),
                    "an Unconfined coroutine was never seen on a loop thread, so the probe cannot see one: the main test proves nothing",
                )
            }
        }

    private companion object {
        const val LOOP_THREAD_PREFIX = "quic-loop-"
        const val ECHOES = 50
        val OP_DEADLINE = 10.seconds
        val WATCHDOG = 2.minutes
    }
}
