@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Phase-0 quiche-on-Apple end-to-end proof: an Apple [QuicheEngine] client and server talk to each
 * other over the POSIX UDP loopback datapath, completing a QUIC handshake and a bidirectional stream
 * round-trip. Drives [QuicheEngine] directly (not via `defaultQuicEngine`, which is still the NW
 * `NetworkEngine` on Apple) so this exercises the new quiche path specifically. If this passes, the
 * full quiche QUIC stack — cinterop API, config/ALPN/cert loading, accept loop, per-peer demux, the
 * driver event loop, and the POSIX datapath — works on macOS.
 */
class AppleQuicheEngineLoopbackTest {
    private fun cert(name: String): String =
        listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
            .firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (run from the repo root or module dir)")

    private val opts =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun quicheClientServerStreamEchoOverLoopback() =
        runQuicTest {
            val tls = QuicTlsConfig(certChainPath = cert("cert.crt"), privKeyPath = cert("cert.key"))
            val server = QuicheEngine.bind(port = 0, host = null, tlsConfig = tls, quicOptions = opts, timeout = 15.seconds)
            try {
                val port = server.port
                val echo = CompletableDeferred<String>()
                val serverJob =
                    launch {
                        server.connections {
                            val stream = acceptStream()
                            val data = stream.read(5.seconds)
                            if (data is ReadResult.Data) stream.write(data.buffer, 5.seconds)
                            stream.close()
                        }
                    }
                delay(100)

                val conn = QuicheEngine.connect("127.0.0.1", port, opts, TransportConfig(), 15.seconds)
                try {
                    val stream = conn.openStream()
                    val sendBuf = BufferFactory.deterministic().allocate(11)
                    sendBuf.writeString("hello quic!", Charset.UTF8)
                    sendBuf.resetForRead()
                    stream.write(sendBuf, 5.seconds)
                    val response = stream.read(5.seconds)
                    echo.complete(
                        if (response is ReadResult.Data) {
                            response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                        } else {
                            "no_data:${response::class.simpleName}"
                        },
                    )
                    stream.close()
                } finally {
                    conn.close()
                }

                assertEquals("hello quic!", withTimeout(10.seconds) { echo.await() })
                serverJob.cancel()
            } finally {
                server.close()
            }
        }
}
