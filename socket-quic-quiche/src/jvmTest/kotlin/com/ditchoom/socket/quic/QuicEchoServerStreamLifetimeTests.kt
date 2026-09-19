package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The echo server's stream lives as long as the connection does. A walk produces radio gaps of
 * tens of seconds as a matter of course; a server that FINs a stream for being quiet turns every
 * one of them into a stream the client can never be echoed on again (#620).
 */
class QuicEchoServerStreamLifetimeTests {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private suspend fun ByteStream.echo(payload: String): ScopedRead<String> {
        val out = BufferFactory.Default.allocate(payload.length)
        try {
            out.writeString(payload, Charset.UTF8)
            out.resetForRead()
            write(out, 5.seconds)
        } finally {
            out.freeIfNeeded()
        }
        return read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
    }

    @Test
    fun theEchoStreamOutlivesAThirtyOneSecondSilence() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicEchoServerStreamLifetimeTests::class) {
                val serverOptions =
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        verifyPeer = false,
                        idleTimeout = 40.seconds,
                        datagrams = DatagramOptions(),
                    )
                val clientOptions =
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        verifyPeer = false,
                        idleTimeout = 40.seconds,
                        keepAliveInterval = 5.seconds,
                    )
                withTimeout(90.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = serverOptions) {
                        val serverJob = launch(Dispatchers.IO) { connections { echoConnection(serverOptions) } }
                        try {
                            withQuicConnection("localhost", port, clientOptions, timeout = 80.seconds) {
                                val stream = openStream()
                                assertEquals(ScopedRead.Data("probe-1;"), stream.echo("probe-1;"))
                                delay(31.seconds)
                                assertEquals(
                                    ScopedRead.Data("probe-2;"),
                                    stream.echo("probe-2;"),
                                    "31 s of silence on a live connection (keepalive 5 s, idle 40 s) ended the echo stream",
                                )
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
