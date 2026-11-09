package com.ditchoom.socket.mainthread

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.mainthread.QuicFixtures.text
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.webtransport.Http3WebTransportConfig
import com.ditchoom.socket.webtransport.connect
import com.ditchoom.socket.webtransport.webTransportSupport
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions

/**
 * A WebTransport session from the main-like thread: connect, open a bidi stream, write, half-close,
 * read the echo, close. The server (an HTTP/3 server with WebTransport on) is bound from it too.
 */
class WebTransportMainThreadSafetyTest {
    private val options =
        QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), verifyPeer = false, idleTimeout = 10.seconds, datagrams = DatagramOptions())
    private val transport = TransportConfig(bufferFactory = BufferFactory.deterministic())

    @Test
    fun sessionBidiRoundTrip() =
        runOnMainLike {
            withHttp3Server(
                port = 0,
                tlsConfig = QuicFixtures.tlsConfig(),
                quicOptions = options,
                connectionOptions = transport,
                webTransport = Http3WebTransportOptions(maxSessions = 4),
                onWebTransport = {
                    val session = accept()
                    val stream = session.incomingBidiStreams.first()
                    val message = stream.readUtf8()
                    stream.write(text("echo:$message"))
                    stream.close()
                },
                onRequest = { response.send(404) },
            ) {
                val session = webTransportSupport().connect("https://localhost:$port/wt", Http3WebTransportConfig(options, transport))
                assertOnMainLike("WebTransport connect")
                try {
                    val stream = session.openBidiStream()
                    assertOnMainLike("openBidiStream")
                    stream.write(text("hello"))
                    (stream as HalfCloseable).shutdownSend()
                    assertEquals("echo:hello", stream.readUtf8())
                    assertOnMainLike("WebTransport read")
                } finally {
                    session.close()
                }
                assertOnMainLike("WebTransport close")
            }
        }

    private suspend fun ByteSource.readUtf8(): String {
        val sb = StringBuilder()
        while (true) {
            when (val result = read()) {
                is ReadResult.Data -> {
                    sb.append(result.buffer.readString(result.buffer.remaining(), Charset.UTF8))
                    result.buffer.freeIfNeeded()
                }
                ReadResult.End, ReadResult.Reset -> return sb.toString()
            }
        }
    }
}
