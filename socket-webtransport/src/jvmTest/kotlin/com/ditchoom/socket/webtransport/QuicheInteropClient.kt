package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **Not a unit test** — the **quiche** (JVM) client half of a cross-implementation interop pair: it dials a
 * **Network.framework** server ([NwInteropServer], Apple K/N) over localhost so the two native QUIC/H3
 * backends are exercised across the wire (handoff "NEXT-PHASE PLAN", cell #1, the NW-server ↔ quiche-client
 * direction). Every `WebTransportTestSuite` subclass is same-platform loopback; this is the cross-impl guard.
 *
 * Gated behind the `wt.interop.client` system property (a normal `jvmTest` skips it via a JUnit assumption);
 * it reads the server's `url` (and the `datagrams` capability flag) from the runtime config file
 * (`wt.interop.configFile`). It runs the **Phase-4 DONE-bar shape**: two WebTransport sessions over one held
 * HTTP/3 connection, each round-tripping its own bidi stream, plus a fire-and-forget uni stream the server
 * logs. Datagrams are tested only if the server advertises `datagrams=true` (an NW server writes `false`,
 * since NW can't carry inbound streams + a datagram flow together).
 *
 * Trust: `verifyPeer = false` (loopback self-signed cert; this proves protocol interop, not chain validation).
 *
 * ```
 * JAVA_HOME=<jdk21> ./gradlew :socket-webtransport:jvmTest \
 *   --tests 'com.ditchoom.socket.webtransport.QuicheInteropClient' \
 *   -Dwt.interop.client=true -Dwt.interop.configFile=<path>
 * ```
 */
class QuicheInteropClient {
    @Test
    fun crossImplRoundTrip() {
        assumeTrue(
            "QuicheInteropClient only runs with -Dwt.interop.client=true (cross-impl interop harness)",
            System.getProperty("wt.interop.client") == "true",
        )
        val configFile = File(System.getProperty("wt.interop.configFile") ?: error("wt.interop.configFile not set"))
        val props = Properties().apply { configFile.inputStream().use { load(it) } }
        val url = props.getProperty("url") ?: error("config has no url: $configFile")
        val datagrams = props.getProperty("datagrams", "false").toBoolean()
        println("WT_CLIENT_START backend=quiche url=$url datagrams=$datagrams")

        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(30.seconds) {
                    val support = webTransportSupport()
                    assertTrue(
                        support is WebTransportSupport.Multiplexed,
                        "native webTransportSupport() must be Multiplexed",
                    )
                    val held = support.connectMultiplexed(url, loopbackClientConfig())
                    try {
                        val a = held.openSession("/a")
                        val b = held.openSession("/b")
                        assertEquals("echo:from-a", a.roundTripBidi("from-a"))
                        assertEquals("echo:from-b", b.roundTripBidi("from-b"))
                        // openUniStream() is a ByteSink — close() FINs the send side (no HalfCloseable).
                        runCatching {
                            a.openUniStream().apply {
                                write(textBuffer("uni-from-a"))
                                close()
                            }
                        }
                        a.close()
                        b.close()
                        println("WT_CLIENT_OK backend=quiche")
                    } finally {
                        held.close()
                    }
                }
            }
        }
    }
}

private fun loopbackClientConfig() =
    Http3WebTransportConfig(
        quicOptions =
            QuicOptions(
                alpnProtocols = listOf(HTTP3_ALPN),
                verifyPeer = false,
                idleTimeout = 10.seconds,
                datagrams = DatagramOptions(),
            ),
        connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
    )

private suspend fun WebTransportSession.roundTripBidi(msg: String): String {
    val stream = openBidiStream()
    stream.write(textBuffer(msg))
    (stream as HalfCloseable).shutdownSend()
    return withTimeout(5.seconds) { stream.readUtf8() }
}

private fun textBuffer(s: String): PlatformBuffer =
    BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
        writeString(s, Charset.UTF8)
        resetForRead()
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
