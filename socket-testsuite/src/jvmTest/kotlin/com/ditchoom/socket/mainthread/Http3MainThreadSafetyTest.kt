package com.ditchoom.socket.mainthread

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.Http3Request
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.QuicOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** An HTTP/3 server and a GET against it, both from the main-like thread, in a JVM that has never used QUIC. */
class Http3MainThreadSafetyTest {
    private val options = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), verifyPeer = false, idleTimeout = 10.seconds)
    private val transport = TransportConfig(bufferFactory = BufferFactory.deterministic())

    @Test
    fun serverAndRequest() =
        runOnMainLike {
            withHttp3Server(
                port = 0,
                tlsConfig = QuicFixtures.tlsConfig(),
                quicOptions = options,
                connectionOptions = transport,
                onRequest = { response.send(204) },
            ) {
                assertOnMainLike("withHttp3Server bind")
                val status =
                    withHttp3Connection("localhost", port, options, transport, 10.seconds) {
                        assertOnMainLike("withHttp3Connection")
                        val response = request(Http3Request(method = "GET", authority = "localhost", path = "/"))
                        assertOnMainLike("request")
                        try {
                            response.readFullBody().freeIfNeeded()
                            response.status
                        } finally {
                            response.close()
                        }
                    }
                assertEquals(204, status)
                assertOnMainLike("connection close")
            }
            assertOnMainLike("server close")
        }
}
