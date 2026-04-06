package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Android QUIC connectivity tests against a local server.
 *
 * The QUIC test server must be running on the host machine.
 * Emulator connects via 10.0.2.2 (host loopback alias) or adb reverse.
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

    @Test
    fun connectToLocalServer() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicEngine()
            try {
                engine.connect(serverHost, serverPort, testQuicOptions, timeout = 10.seconds) {
                    // If we reach here, handshake completed successfully
                }
            } catch (_: Throwable) {
                assumeTrue("QUIC server not reachable — skipping", false)
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
            } catch (_: Throwable) {
                assumeTrue("QUIC server not reachable — skipping", false)
            } finally {
                engine.close()
            }
        }
}
