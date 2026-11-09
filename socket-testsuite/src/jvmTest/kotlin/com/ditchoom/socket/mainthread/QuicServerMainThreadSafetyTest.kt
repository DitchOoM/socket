package com.ditchoom.socket.mainthread

import com.ditchoom.socket.mainthread.QuicFixtures.echoRoundTrip
import com.ditchoom.socket.mainthread.QuicFixtures.serveEcho
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The QUIC server from the main-like thread, in a JVM that has never used QUIC (so its bind loads the
 * native library): bind, accept, serve one stream, close. The client runs off the main-like thread.
 */
class QuicServerMainThreadSafetyTest {
    @Test
    fun firstBindAcceptServeClose() =
        runOnMainLike {
            withQuicServer(port = 0, tlsConfig = QuicFixtures.tlsConfig(), quicOptions = QuicFixtures.options) {
                assertOnMainLike("withQuicServer bind")
                val echo = launch { serveEcho() }
                try {
                    withContext(Dispatchers.IO) {
                        withQuicConnection("localhost", port, QuicFixtures.options, timeout = 10.seconds) {
                            echoRoundTrip(checkThread = false)
                        }
                    }
                } finally {
                    echo.cancel()
                }
            }
            assertOnMainLike("server close")
        }
}
