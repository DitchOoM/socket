package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.JniQuicheApi
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A stream whose first byte is this is echoed; any other stream is drained and answered with FIN alone. */
internal const val ECHO_MARKER: Byte = 'E'.code.toByte()

internal val IO_TIMEOUT: Duration = 30.seconds

/**
 * One in-process QUIC server on 127.0.0.1 and one client connection to it, both held open for the
 * life of a JMH trial so a benchmark measures steady-state stream traffic, not handshakes.
 *
 * withQuicServer and withQuicConnection are scoped blocks, so they run on a dedicated thread that
 * parks inside both until [close]. The connected [QuicScope] escapes to the benchmark thread, which
 * opens streams on it from its own `runBlocking`: that is the shape of an application calling into
 * a long-lived connection from a thread it owns.
 */
internal class QuicLoopback : AutoCloseable {
    private val ready = CompletableDeferred<QuicScope>()
    private val stop = CompletableDeferred<Unit>()

    private val runner =
        thread(name = "quic-loopback", isDaemon = true) {
            try {
                runBlocking(Dispatchers.IO) {
                    withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tlsConfig(), quicOptions = OPTIONS) {
                        val serverJob = launch { connections { sinkOrEcho() } }
                        try {
                            // The timeout bounds the handshake AND the whole block, which lives as long as the trial.
                            withQuicConnection("127.0.0.1", port, OPTIONS, timeout = 24.hours) {
                                ready.complete(this)
                                stop.await()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            } catch (t: Throwable) {
                ready.completeExceptionally(t)
            }
        }

    /** The client side of the connection. */
    val client: QuicScope = runBlocking { ready.await() }

    override fun close() {
        stop.complete(Unit)
        runner.join(10_000)
    }

    companion object {
        val OPTIONS =
            QuicOptions(
                alpnProtocols = listOf("bench"),
                verifyPeer = false,
                // Longer than any pause JMH takes between iterations of one trial.
                idleTimeout = 10.minutes,
                migration = MigrationPolicy.Forbidden,
                flowControl =
                    FlowControl(
                        initialMaxData = 64L * 1024 * 1024,
                        initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                        initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                        initialMaxStreamsBidi = 1_000_000,
                    ),
            )

        private fun tlsConfig(): QuicTlsConfig {
            val dir =
                System.getProperty("bench.certDir")
                    ?: error("bench.certDir is not set: run the benchmarks through Gradle (:benchmark-jvm:benchmark)")
            return QuicTlsConfig(certChainPath = File(dir, "cert.crt").path, privKeyPath = File(dir, "cert.key").path)
        }
    }
}

/**
 * Names the quiche backend this JVM loaded ("ffm" or "jni") and fails if it is not the one the build
 * asked for through `quiche.expectedJvmBackend`. Printed once per fork, so the JMH log says which
 * backend every number came from.
 */
internal fun assertQuicheBackend(): String {
    val actual = loadedQuicheBackend()
    val expected = System.getProperty("quiche.expectedJvmBackend")
    check(expected == null || expected == actual) {
        "quiche backend mismatch: the build asked for '$expected' but this JVM loaded '$actual'. " +
            "The numbers would be reported under the wrong backend, so the run stops here."
    }
    println("# quiche backend: $actual (${loadQuicheApi()::class.java.name}) on JDK ${System.getProperty("java.version")}")
    return actual
}

/**
 * "jni" or "ffm". Decided by identity with [JniQuicheApi] rather than by class name, so it still answers
 * correctly inside a shrunk and obfuscated jar (see ShrunkConsumerProbe). The FFM class lives under
 * META-INF/versions/21 and is not on this module's compile classpath, so "not JNI" is how it is named.
 * The recv_info guard (QUICHE_RECV_INFO_GUARD) wraps either backend and would hide which one it wraps.
 */
internal fun loadedQuicheBackend(): String {
    val api = loadQuicheApi()
    check(!api::class.java.name.endsWith("RecvInfoGuardQuicheApi")) {
        "The recv_info guard is on (QUICHE_RECV_INFO_GUARD); unset it to benchmark, it wraps every call."
    }
    return if (api === JniQuicheApi) "jni" else "ffm"
}

private suspend fun QuicScope.sinkOrEcho() {
    coroutineScope {
        streams().collect { stream ->
            launch { serveStream(stream) }
        }
    }
}

private suspend fun serveStream(stream: QuicByteStream) {
    try {
        var echo: Boolean? = null
        while (true) {
            when (val r = stream.read(10.minutes)) {
                is ReadResult.Data ->
                    try {
                        if (echo == null) echo = r.buffer.remaining() > 0 && r.buffer[r.buffer.position()] == ECHO_MARKER
                        if (echo == true) stream.writeFully(r.buffer, IO_TIMEOUT)
                    } finally {
                        r.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> break
            }
        }
        stream.shutdownSend()
    } catch (_: Exception) {
        // The client went away (trial teardown); nothing to report.
    } finally {
        stream.close()
    }
}
