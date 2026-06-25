package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises BOTH Apple QUIC backends on one runner.
 *
 * [NetworkEngine] normally picks the OS-26 Swift `NetworkConnection<QUIC>` backend vs the legacy
 * `nw_connection_group` backend purely from the host OS version (see [NetworkEngine.backendOverrideForTest]).
 * So on any single machine only one path runs and the other is never tested — in particular, on a
 * macOS/iOS-26 runner the legacy datagram+`PreferStreams` *fallback* (what every pre-26 client actually
 * executes: HTTP/3 needs inbound streams, so the group backend skips datagram-flow extraction and
 * datagrams report unavailable) is otherwise dead code in CI. These tests force each backend via the
 * internal override and assert its documented contract, restoring the override in a `finally`.
 *
 * Both bodies use the same `datagrams + PreferStreams` options that the HTTP/3 / WebTransport stack
 * sets through `forHttp3()` — the exact config whose handling diverges between the two backends.
 */
class AppleBackendSelectionTest {
    private val datagramsPreferStreams =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            datagrams = DatagramOptions(),
            datagramStreamConflictPolicy = DatagramStreamConflictPolicy.PreferStreams,
        )

    /**
     * Legacy `nw_connection_group` path with `datagrams + PreferStreams`: the documented pre-26
     * fallback. Datagrams report [MaxDatagramSize.Unavailable] (the group backend skips flow extraction
     * so inbound streams keep working) while a bidi stream still round-trips. This is the path a
     * macOS-26 runner would otherwise never execute.
     */
    @Test
    fun forcedLegacyBackend_datagramsWithPreferStreams_datagramsUnavailableButStreamsWork() =
        runQuicTest {
            if (shouldSkipQuicHarnessOnSimulator()) return@runQuicTest
            NetworkEngine.backendOverrideForTest = AppleQuicBackend.Legacy
            try {
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = datagramsPreferStreams) {
                    val echoed = CompletableDeferred<String>()
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                val data = stream.read(5.seconds)
                                if (data is ReadResult.Data) stream.write(data.buffer, 5.seconds)
                                stream.close()
                            }
                        }
                    delay(100)

                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, datagramsPreferStreams, timeout = 10.seconds) {
                                // The legacy backend with PreferStreams advertises but never extracts a
                                // datagram flow, so datagrams are unavailable here (the pre-26 limitation).
                                assertEquals(
                                    MaxDatagramSize.Unavailable,
                                    maxDatagramSize(),
                                    "legacy nw_connection_group with PreferStreams must not deliver datagrams",
                                )
                                // ...but inbound/outbound streams still work, which is the whole reason the
                                // group backend skips extraction.
                                val stream = openStream()
                                val sendBuf = BufferFactory.deterministic().allocate(11)
                                sendBuf.writeString("hello quic!", Charset.UTF8)
                                sendBuf.resetForRead()
                                stream.write(sendBuf, 5.seconds)
                                val response = stream.read(5.seconds)
                                echoed.complete(
                                    if (response is ReadResult.Data) {
                                        response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                                    } else {
                                        "no_data"
                                    },
                                )
                                stream.close()
                            }
                        }

                    try {
                        assertEquals("hello quic!", withTimeout(10.seconds) { echoed.await() })
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            } finally {
                NetworkEngine.backendOverrideForTest = null
            }
        }

    /**
     * OS-26 Swift `NetworkConnection<QUIC>` path with the SAME `datagrams + PreferStreams` options:
     * datagrams AND streams coexist (issue #173's fix), so [maxDatagramSize] is [MaxDatagramSize.Bytes]
     * (vs the legacy path's [MaxDatagramSize.Unavailable] above — the one observable that proves the
     * override switched backends) while the same bidi stream round-trips. Skipped below macOS/iOS 26,
     * where the Swift API is unavailable. The full datagram *round-trip* over Swift is covered
     * deterministically by `socket-http3`'s `webTransport_datagramRoundTrip`; a raw lossy-datagram echo
     * here would race the server handler's connection teardown, so this asserts availability + streams.
     */
    @Test
    fun forcedSwiftBackend_datagramsWithPreferStreams_datagramsAvailableAndStreamsWork() =
        runQuicTest {
            if (shouldSkipQuicHarnessOnSimulator()) return@runQuicTest
            if (!isAppleOS26OrLater()) return@runQuicTest
            NetworkEngine.backendOverrideForTest = AppleQuicBackend.Swift
            try {
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = datagramsPreferStreams) {
                    val echoed = CompletableDeferred<String>()
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                val data = stream.read(5.seconds)
                                if (data is ReadResult.Data) stream.write(data.buffer, 5.seconds)
                                stream.close()
                            }
                        }
                    delay(100)

                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, datagramsPreferStreams, timeout = 10.seconds) {
                                assertIs<MaxDatagramSize.Bytes>(
                                    maxDatagramSize(),
                                    "OS-26 Swift backend must deliver datagrams alongside streams",
                                )
                                val stream = openStream()
                                val sendBuf = BufferFactory.deterministic().allocate(11)
                                sendBuf.writeString("hello quic!", Charset.UTF8)
                                sendBuf.resetForRead()
                                stream.write(sendBuf, 5.seconds)
                                val response = stream.read(5.seconds)
                                echoed.complete(
                                    if (response is ReadResult.Data) {
                                        response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                                    } else {
                                        "no_data"
                                    },
                                )
                                stream.close()
                            }
                        }

                    try {
                        assertEquals("hello quic!", withTimeout(10.seconds) { echoed.await() })
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            } finally {
                NetworkEngine.backendOverrideForTest = null
            }
        }
}
