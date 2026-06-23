@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **Not a unit test** — the **Network.framework** (Apple K/N) client half of a cross-impl interop pair: it
 * dials a **quiche** server ([BrowserInteropServer], JVM) over localhost so the two native QUIC/H3 backends
 * are exercised across the wire (handoff "NEXT-PHASE PLAN", cell #1, the quiche-server ↔ NW-client
 * direction).
 *
 * Gated by the **`WT_INTEROP_CLIENT` environment variable** (K/N has no JVM system properties) → an ordinary
 * `macosArm64Test` run skips it via an early return. It reads the server's `url` from the runtime config file
 * (`WT_INTEROP_CONFIG_FILE`) and runs the **Phase-4 DONE-bar shape**: two WebTransport sessions over one held
 * HTTP/3 connection, each round-tripping its own bidi stream, plus a fire-and-forget uni stream the server
 * logs. Streams only — `datagrams=false` (NW can't carry inbound streams + a datagram flow together).
 *
 * Trust: `verifyPeer = false` (the loopback cert is self-signed; this proves protocol interop, not chain
 * validation — mirroring the loopback `WebTransportTestSuite`).
 */
class NwInteropClient {
    @Test
    fun crossImplRoundTrip() {
        if (env("WT_INTEROP_CLIENT") != "true") return // gated: skip on ordinary macosArm64Test runs

        val configFile = env("WT_INTEROP_CONFIG_FILE") ?: error("WT_INTEROP_CONFIG_FILE not set")
        val config = parseConfig(readTextFile(configFile))
        val url = config["url"] ?: error("config has no url: $config")
        println("WT_CLIENT_START backend=nw url=$url")

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
                        // Exercise the uni path too (server logs WT_UNI_RECEIVED); fire-and-forget.
                        // openUniStream() is a ByteSink — close() FINs the send side (no HalfCloseable).
                        runCatching {
                            a.openUniStream().apply {
                                write(textBuffer("uni-from-a"))
                                close()
                            }
                        }
                        a.close()
                        b.close()
                        println("WT_CLIENT_OK backend=nw")
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
