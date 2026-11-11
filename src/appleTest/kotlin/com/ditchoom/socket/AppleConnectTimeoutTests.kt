package com.ditchoom.socket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `connectTimeout` bounds the whole connect, TLS handshake included. Network.framework's own
 * connection timeout covers only the TCP handshake, so a server that accepts TCP and then says nothing
 * used to hold `open()` for as long as it stayed silent: 8 s against a 2 s `connectTimeout`, measured.
 */
class AppleConnectTimeoutTests {
    @Test
    fun aTlsConnectToASilentServerTimesOutAtTheConnectTimeout() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val accepted = server.bind()
            val release = CompletableDeferred<Unit>()
            val serverJob =
                launch(Dispatchers.Default) {
                    accepted.collect { peer ->
                        // Accept the TCP connection, then never answer the ClientHello.
                        try {
                            release.await()
                        } finally {
                            peer.close()
                        }
                    }
                }
            try {
                val connectTimeout = 1.seconds
                val start = TimeSource.Monotonic.markNow()
                val socket = ClientSocket.allocate(TransportConfig.tlsDefault().copy(connectTimeout = connectTimeout))
                val error =
                    try {
                        socket.open(server.port(), "127.0.0.1")
                        socket.close()
                        fail("a TLS connect to a server that never answers must not succeed")
                    } catch (e: SocketTimeoutException) {
                        e
                    }
                val elapsed = start.elapsedNow()
                val context = assertIs<TimeoutContext.Connect>(error.context)
                assertEquals(connectTimeout, context.deadline)
                assertEquals(server.port(), context.port)
                // The server stays silent until the test ends, so only the connect's own bound can end it.
                assertTrue(elapsed < SILENCE_OUTLIVES_THE_BOUND, "connect ended after $elapsed, against a $connectTimeout bound")
            } finally {
                release.complete(Unit)
                server.close()
                serverJob.cancel()
            }
        }

    private companion object {
        /** Well short of the 30 s test timeout; the bound under test is 1 s. */
        val SILENCE_OUTLIVES_THE_BOUND = 10.seconds
    }
}
