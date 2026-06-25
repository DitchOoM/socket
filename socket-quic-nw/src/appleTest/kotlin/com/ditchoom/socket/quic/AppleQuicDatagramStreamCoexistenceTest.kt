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
 * The Apple OS-26 Swift `NetworkConnection<QUIC>` backend carries datagrams AND inbound streams on the
 * SAME connection (issue #173). With the `datagrams + PreferStreams` options the HTTP/3 / WebTransport
 * stack sets through `forHttp3()`, [maxDatagramSize] therefore reports [MaxDatagramSize.Bytes] while a
 * bidi stream still round-trips — the coexistence the removed legacy `nw_connection_group` backend could
 * not provide (it had to skip datagram-flow extraction to keep inbound streams working).
 */
class AppleQuicDatagramStreamCoexistenceTest {
    private val datagramsPreferStreams =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            datagrams = DatagramOptions(),
            datagramStreamConflictPolicy = DatagramStreamConflictPolicy.PreferStreams,
        )

    @Test
    fun datagramsCoexistWithStreamsUnderPreferStreams() =
        runQuicTest {
            if (shouldSkipQuicHarnessOnSimulator()) return@runQuicTest
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
        }
}
