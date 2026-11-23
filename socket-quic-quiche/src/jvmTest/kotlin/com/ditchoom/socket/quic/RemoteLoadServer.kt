package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Load server for remote clients (benchmarks/android-micro's RemoteQuicLoad): a stream whose first byte
 * is 'E' is echoed, any other stream is drained and answered with FIN alone.
 *
 * Usage: RemoteLoadServerKt <cert.crt> <cert.key> <port> [capture-dir]
 *
 * With a capture directory, every accepted connection writes `conn-NNNNN.trace` (replay trace),
 * `conn-NNNNN.sqlog` and `conn-NNNNN.keys` (TLS secrets) there.
 */
fun main(args: Array<String>) {
    val tls = QuicTlsConfig(certChainPath = args[0], privKeyPath = args[1])
    val base =
        QuicOptions(
            alpnProtocols = listOf("bench"),
            verifyPeer = false,
            idleTimeout = 10.minutes,
            flowControl =
                FlowControl(
                    initialMaxData = 64L * 1024 * 1024,
                    initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                    initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                    initialMaxStreamsBidi = 1_000_000,
                ),
        )
    val options = if (args.size > 3) base.copy(trace = perConnectionCapture(File(args[3]))) else base
    runBlocking(Dispatchers.IO) {
        withQuicServer(port = args[2].toInt(), tlsConfig = tls, quicOptions = options) {
            println("READY port=$port")
            System.out.flush()
            connections { sinkOrEcho() }
        }
    }
}

private fun perConnectionCapture(dir: File): QuicTraceCapture {
    dir.mkdirs()
    val next = AtomicInteger()
    return QuicTraceCapture(
        captureFor = {
            val name = "conn-%05d".format(next.getAndIncrement())
            val trace = File(dir, "$name.trace")
            QuicConnectionCapture(
                // Delivery is one event at a time, so appending per event is safe and holds no descriptor open.
                sink = TraceSink { event -> trace.appendText("$event\n") },
                qlog = QlogTarget.File(File(dir, "$name.sqlog").absolutePath),
                trafficSecrets = TrafficSecretsLog.File(File(dir, "$name.keys").absolutePath),
            )
        },
        qlog = QlogDeclaration.NotBudgeted,
    )
}

private suspend fun QuicScope.sinkOrEcho() {
    coroutineScope {
        streams().collect { stream -> launch { serve(stream) } }
    }
}

private suspend fun serve(stream: QuicByteStream) {
    try {
        var mode = StreamMode.Undecided
        while (true) {
            when (val r = stream.read(10.minutes)) {
                is ReadResult.Data ->
                    try {
                        if (mode == StreamMode.Undecided) {
                            val echo = r.buffer.remaining() > 0 && r.buffer[r.buffer.position()] == 'E'.code.toByte()
                            mode = if (echo) StreamMode.Echo else StreamMode.Sink
                        }
                        if (mode == StreamMode.Echo) stream.writeFully(r.buffer, 30.seconds)
                    } finally {
                        r.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> break
            }
        }
        stream.shutdownSend()
    } catch (_: Exception) {
        // The client went away.
    } finally {
        stream.close()
    }
}

private enum class StreamMode { Undecided, Echo, Sink }
