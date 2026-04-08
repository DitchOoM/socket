package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Android QUIC connectivity tests against a local server.
 *
 * Run via `./gradlew :socket-quic:androidQuicIntegrationTest` which starts
 * the test server on the host and configures `adb reverse`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicConnectivityTests {
    private val serverHost = "10.0.2.2"
    private val serverPort = 4433

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /** Skip the entire class if the test server isn't reachable. */
    @Before
    fun checkServerReachable() {
        val engine = defaultQuicEngine()
        try {
            runBlocking(Dispatchers.IO) {
                engine.connect(serverHost, serverPort, testQuicOptions, timeout = 5.seconds) {}
            }
        } catch (_: Throwable) {
            engine.close()
            assumeTrue("QUIC test server not reachable at $serverHost:$serverPort — run androidQuicIntegrationTest", false)
        }
        engine.close()
    }

    @Test
    fun connectToLocalServer() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicEngine()
            try {
                engine.connect(serverHost, serverPort, testQuicOptions, timeout = 10.seconds) {
                    // If we reach here, handshake completed successfully
                }
            } finally {
                engine.close()
            }
        }

    @Test
    fun echoOverQuic() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicEngine()
            try {
                engine.connect(serverHost, serverPort, testQuicOptions, timeout = 10.seconds) {
                    val stream = openStream()
                    val sendBuf = BufferFactory.Default.allocate(5)
                    sendBuf.writeString("hello", Charset.UTF8)
                    sendBuf.resetForRead()
                    stream.write(sendBuf, 5.seconds)

                    val response = stream.read(5.seconds)
                    assertIs<com.ditchoom.buffer.flow.ReadResult.Data>(response)

                    stream.close()
                }
            } finally {
                engine.close()
            }
        }
}
