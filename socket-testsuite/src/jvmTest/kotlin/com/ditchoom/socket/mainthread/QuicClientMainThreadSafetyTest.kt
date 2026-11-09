package com.ditchoom.socket.mainthread

import com.ditchoom.socket.mainthread.QuicFixtures.echoRoundTrip
import com.ditchoom.socket.mainthread.QuicFixtures.serveEcho
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.seconds

/**
 * The QUIC client from the main-like thread, in a JVM that has never used QUIC: its fresh JVM (the
 * task forks one per class) makes this connect the one that extracts and loads the native library.
 */
class QuicClientMainThreadSafetyTest {
    @Test
    fun firstConnectThenStreamRoundTrip() {
        // A UDP port that never answers, so the first-ever connect needs no QUIC server — a server bound
        // first would load the native library itself and hide the client's load.
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
            runOnMainLike {
                assertFails {
                    withQuicConnection("localhost", silent.localPort, QuicFixtures.options, timeout = 1.seconds) {}
                }
                assertOnMainLike("a first connect that timed out")

                // Then a full stream round trip: the server runs off the main-like thread, the client on it.
                val mainLike = coroutineContext[ContinuationInterceptor]!!
                withContext(Dispatchers.IO) {
                    withQuicServer(port = 0, tlsConfig = QuicFixtures.tlsConfig(), quicOptions = QuicFixtures.options) {
                        val echo = launch { serveEcho() }
                        try {
                            withContext(mainLike) {
                                withQuicConnection("localhost", port, QuicFixtures.options, timeout = 10.seconds) {
                                    assertOnMainLike("withQuicConnection")
                                    echoRoundTrip()
                                }
                                assertOnMainLike("connection close")
                            }
                        } finally {
                            echo.cancel()
                        }
                    }
                }
            }
        }
    }
}
