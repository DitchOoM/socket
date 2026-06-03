@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.http3.Http3Frame
import com.ditchoom.socket.http3.Http3FrameCodec
import com.ditchoom.socket.http3.Http3Setting
import com.ditchoom.socket.http3.Http3SettingId
import com.ditchoom.socket.http3.VarIntCodec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Apple unidirectional-stream spike (issue #86 back-half investigation).
 *
 * Proves Network.framework can open a client-initiated UNIDIRECTIONAL QUIC stream — the
 * stream HTTP/3 needs for its control + QPACK encoder/decoder channels (RFC 9114 §6.2) —
 * via [openUniStream] (backed by `nw_helper_quic_group_extract_uni_stream`), write the H3
 * control-stream type byte + a SETTINGS frame, and have a real h3 server accept it (the
 * server opens its own control stream back).
 *
 * This hits a public h3 endpoint, so it is a DIAGNOSTIC, not a gate:
 *  - no QUIC/UDP egress, or the handshake never completes → the test SKIPS (returns), so it
 *    never flakes the suite;
 *  - a reachable server that rejects/​resets the uni stream → the test FAILS — that rejection
 *    is exactly the signal the spike exists to surface.
 */
class AppleUniStreamSpikeTests {
    @Test
    fun appleOpensUniStream_andServerAcceptsH3Control() {
        if (shouldSkipQuicHarnessOnSimulator()) return
        runBlocking {
            // True only once the QUIC handshake has completed and the block body runs, so a
            // throw before this is a connectivity skip and a throw after is a real failure.
            var connected = false
            try {
                val options = QuicOptions(alpnProtocols = listOf("h3"), idleTimeout = 10.seconds)
                withQuicConnection("cloudflare-quic.com", 443, options, timeout = 10.seconds) {
                    connected = true

                    val uni = openUniStream()
                    assertTrue(
                        uni.streamId.isUnidirectional,
                        "openUniStream() must yield a unidirectional stream id, got ${uni.streamId}",
                    )

                    // Client control stream: type 0x00, then SETTINGS (static-table mode:
                    // QPACK table capacity 0, blocked streams 0).
                    val control = BufferFactory.Default.allocate(64)
                    VarIntCodec.encode(control, 0x00L, EncodeContext.Empty)
                    Http3FrameCodec.encode(
                        control,
                        Http3Frame.Settings(
                            listOf(
                                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                                Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
                            ),
                        ),
                        EncodeContext.Empty,
                    )
                    control.resetForRead()
                    val payloadSize = control.remaining()
                    val written = uni.write(control)
                    assertTrue(
                        written.count == payloadSize,
                        "control-stream write truncated: ${written.count}/$payloadSize bytes",
                    )

                    // End-to-end confirmation: a compliant h3 server opens its own control
                    // stream (type 0x00) shortly after the handshake. Best-effort within the
                    // window — its arrival proves both directions of uni streaming are healthy.
                    val serverStream = withTimeoutOrNull(8.seconds) { acceptStream() }
                    if (serverStream != null) {
                        when (val first = withTimeoutOrNull(8.seconds) { serverStream.read() }) {
                            is ReadResult.Data -> {
                                val typeByte = first.buffer.readByte().toInt() and 0xFF
                                println("apple uni-stream spike: server opened a stream, first byte=0x${typeByte.toString(16)}")
                            }
                            else -> println("apple uni-stream spike: server stream had no readable data ($first)")
                        }
                    } else {
                        println("apple uni-stream spike: no server-initiated stream within window (uni write still succeeded)")
                    }
                }
            } catch (t: Throwable) {
                if (!connected) {
                    println("apple uni-stream spike SKIPPED — QUIC egress/handshake unavailable: ${t::class.simpleName}: ${t.message}")
                    return@runBlocking
                }
                throw t // reached the server, so this is a genuine uni-stream failure
            }
        }
    }
}
