package com.ditchoom.socket.benchmark.android

import androidx.test.platform.app.InstrumentationRegistry
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
 * One in-process QUIC server on 127.0.0.1 and one client connection to it, held open across a
 * benchmark so it measures steady-state stream traffic, not handshakes. The Android twin of
 * benchmarks/jvm's QuicLoopback: the scoped withQuicServer/withQuicConnection blocks park on their own
 * thread, and the connected [QuicScope] escapes to the benchmark thread.
 */
internal class AndroidQuicLoopback : AutoCloseable {
    private val ready = CompletableDeferred<QuicScope>()
    private val stop = CompletableDeferred<Unit>()

    private val runner =
        thread(name = "quic-loopback", isDaemon = true) {
            try {
                runBlocking(Dispatchers.IO) {
                    withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tlsConfig(), quicOptions = OPTIONS) {
                        val serverJob = launch { connections { sinkOrEcho() } }
                        try {
                            // The timeout bounds the handshake AND the whole block, which lives as long as the benchmark.
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

        /** quiche reads the certificate from a path, so the bundled resource is copied out first. */
        private fun tlsConfig(): QuicTlsConfig {
            val dir = InstrumentationRegistry.getInstrumentation().context.cacheDir

            fun extract(name: String): String {
                val out = File(dir, name)
                val input =
                    AndroidQuicLoopback::class.java.classLoader!!.getResourceAsStream("certs/$name")
                        ?: error("certs/$name is not in the test APK")
                input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
                return out.absolutePath
            }
            return QuicTlsConfig(certChainPath = extract("cert.crt"), privKeyPath = extract("cert.key"))
        }
    }
}

/** Android always runs the JNI backend; this fails loudly if that ever stops being what loaded. */
internal fun assertJniBackend() {
    val api = loadQuicheApi()
    check(api === JniQuicheApi) { "expected the JNI quiche backend on Android, got ${api::class.java.name}" }
}

private suspend fun QuicScope.sinkOrEcho() {
    coroutineScope {
        streams().collect { stream -> launch { serveStream(stream) } }
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
        // The client went away (teardown).
    } finally {
        stream.close()
    }
}

/** Reads until [expected] bytes have arrived; returns the count (fewer only if the peer ended first). */
internal suspend fun QuicByteStream.readExactly(expected: Int): Int {
    var got = 0
    while (got < expected) {
        when (val r = read(IO_TIMEOUT)) {
            is ReadResult.Data ->
                try {
                    got += r.buffer.remaining()
                } finally {
                    r.buffer.freeIfNeeded()
                }
            ReadResult.End, ReadResult.Reset -> return got
        }
    }
    return got
}

/** Reads until the peer's FIN; returns how many bytes arrived before it. */
internal suspend fun QuicByteStream.readToEnd(): Int {
    var got = 0
    while (true) {
        when (val r = read(IO_TIMEOUT)) {
            is ReadResult.Data ->
                try {
                    got += r.buffer.remaining()
                } finally {
                    r.buffer.freeIfNeeded()
                }
            ReadResult.End, ReadResult.Reset -> return got
        }
    }
}
