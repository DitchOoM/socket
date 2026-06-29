package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 1 conformance suite: tests that talk to the local docker-compose
 * harness instead of the public internet. Same `commonTest` source set,
 * so every platform with `FULL_SOCKET_ACCESS` runs the identical
 * assertions against the identical endpoints.
 *
 * Each test short-circuits via [isHarnessAvailable] when the stack isn't
 * up (no Docker locally, Windows runner, `HARNESS_DISABLED=true`, …) so
 * the suite stays green without the harness.
 */
class HarnessConformanceTests {
    /**
     * L0 round-trip on the TCP echo (`HarnessConfig.echoPort`). The cheapest
     * end-to-end signal that connect / write / read / close all work against
     * a real peer — no public-host flakiness, no decode fragility.
     */
    @Test
    fun harnessEchoRoundTrip() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val payload = "harness-echo-roundtrip"
            val echoed =
                ClientSocket.connect(
                    port = HarnessConfig.echoPort,
                    hostname = harnessHost(),
                    config = TransportConfig(connectTimeout = 5.seconds),
                ) { socket ->
                    socket.writeString(payload, Charset.UTF8, 2.seconds)
                    val buf = socket.readBuffer(2.seconds)
                    buf.readString(buf.remaining(), Charset.UTF8)
                }
            assertTrue(
                echoed.startsWith(payload),
                "expected echoed payload to start with '$payload', got '$echoed'",
            )
        }

    /**
     * L0 HTTP GET against the harness nginx (`HarnessConfig.httpPort`) —
     * deterministic equivalent of the legacy `httpRawSocket*` public-host
     * tests. Asserts on the ASCII status-line bytes only, so it is
     * insensitive to where the kernel happens to split the response into
     * TCP reads (the UTF-8 straddle that bit `tlsWithSni`).
     */
    @Test
    fun harnessHttpStatusLine() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val prefix =
                ClientSocket.connect(
                    port = HarnessConfig.httpPort,
                    hostname = harnessHost(),
                    config = TransportConfig(connectTimeout = 5.seconds),
                ) { socket ->
                    val request =
                        "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n"
                            .toReadBuffer(Charset.UTF8)
                    socket.write(request, 2.seconds)
                    val firstChunk = socket.readBuffer(5.seconds)
                    buildString {
                        repeat(minOf(5, firstChunk.remaining())) {
                            append(firstChunk.readByte().toInt().toChar())
                        }
                    }
                }
            assertEquals("HTTP/", prefix, "expected HTTP/ status-line prefix")
        }
}
