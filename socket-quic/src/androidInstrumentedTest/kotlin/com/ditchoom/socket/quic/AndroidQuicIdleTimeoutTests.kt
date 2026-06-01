package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Idle-timeout / keepalive tests on Android — the Android port of the JVM/Linux
 * [QuicIdleTimeoutTestSuite] (issue #87, suite #5). `androidInstrumentedTest` can't see `commonTest`, so
 * this is a self-contained parallel copy.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicIdleTimeoutTests {
    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    private fun options(idleTimeout: Duration) = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = idleTimeout)

    @Test
    fun idleConnectionTimesOutWithCleanEnd() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    val opts = options(IDLE_TIMEOUT)
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = opts) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    acceptStream()
                                    awaitCancellation()
                                }
                            }
                        try {
                            withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds) {
                                val stream = openStream()
                                stream.writeString("hi")
                                val result = stream.read(READ_TIMEOUT)
                                assertTrue(
                                    result is ReadResult.End,
                                    "idle timeout should close the stream cleanly (End) within $READ_TIMEOUT, got $result",
                                )
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun activityKeepsConnectionAlivePastIdleTimeout() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    val opts = options(KEEPALIVE_IDLE)
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = opts) {
                        val serverJob = launch(Dispatchers.IO) { echoEveryStream() }
                        try {
                            withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds) {
                                val stream = openStream()
                                for (round in 0 until KEEPALIVE_ROUNDS) {
                                    assertEquals(
                                        "ka-$round",
                                        stream.echoOnce("ka-$round"),
                                        "echo $round failed — connection idle-closed despite activity (idle timer not reset)",
                                    )
                                    delay(KEEPALIVE_GAP)
                                }
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun QuicServer.echoEveryStream() {
        connections {
            val stream = acceptStream()
            while (true) {
                val data = stream.read(KEEPALIVE_IDLE + 5.seconds)
                if (data is ReadResult.Data) {
                    stream.write(data.buffer, 5.seconds)
                } else {
                    break
                }
            }
            stream.close()
        }
    }

    private suspend fun QuicByteStream.writeString(payload: String) {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
    }

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        writeString(payload)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) {
            val s = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
            resp.buffer.freeIfNeeded()
            s
        } else {
            "no_data"
        }
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    private companion object {
        private val IDLE_TIMEOUT = 2.seconds
        private val READ_TIMEOUT = 10.seconds
        private val KEEPALIVE_IDLE = 4.seconds
        private val KEEPALIVE_GAP = 1.seconds
        private const val KEEPALIVE_ROUNDS = 6
    }
}
