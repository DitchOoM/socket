package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * **Passive** connection migration (RFC 9000 §9.3 NAT rebinding) on Android — the Android port of
 * the JVM [QuicPassiveMigrationTests] (issue #72 Task 3). The client never calls [QuicScope.migrate];
 * the path's source address changes underneath it, as a NAT rebind would.
 *
 * Both ends run in one Android process via [withQuicServer]. A userspace [RebindingUdpProxy] sits
 * between client and server (no root / netns / tc): the client talks to the proxy, the proxy
 * forwards to the in-process server, and mid-stream the proxy swaps its upstream socket for one with
 * a fresh source port. From the server's view that's the same connection (unchanged DCID) arriving
 * from a new 4-tuple — a passive rebind. We assert the stream still round-trips, proving the Android
 * server keeps the stream alive via per-source recv_info + sendInfo.to routing.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicPassiveMigrationTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    private suspend fun QuicByteStream.echoOnce(
        payload: String,
        readTimeout: Duration,
    ): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(readTimeout)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun streamSurvivesPassiveSourceRebind() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                // Generous whole-test budget: connect + echo + a NAT rebind + a post-rebind echo.
                // A passive rebind drops in-flight packets, so the "after" round-trip can need a QUIC
                // PTO-driven retransmit and/or path validation — legitimately several seconds under
                // loss + device load. (Mirrors the JVM/Linux QuicPassiveMigrationTestSuite fix.)
                withTimeout(40.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        val data = stream.read(8.seconds)
                                        if (data is ReadResult.Data) {
                                            stream.write(data.buffer, 5.seconds)
                                        } else {
                                            break
                                        }
                                    }
                                    stream.close()
                                }
                            }
                        delay(100)

                        val proxy = RebindingUdpProxy(serverPort = port)
                        try {
                            // Run the client INLINE (not in a child launch funneling results through
                            // CompletableDeferred.await): a per-op `withTimeout` throws a
                            // CancellationException, which would cancel a child coroutine silently and
                            // leave the await to time out opaquely, masking the real cause. Inline,
                            // any failure propagates straight to the test with its true cause/phase.
                            withQuicConnection("127.0.0.1", proxy.proxyPort, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                assertEquals("before", stream.echoOnce("before", readTimeout = 5.seconds))

                                // Passive rebind: the proxy's source toward the server changes, with
                                // NO client-side migrate(). The server must keep the stream alive via
                                // per-source recv_info + sendInfo.to routing.
                                proxy.rebind()

                                // Allow the post-rebind round-trip to absorb migration recovery
                                // (retransmit + path validation), bounded under the 10s idle timeout.
                                assertEquals(
                                    "after",
                                    stream.echoOnce("after", readTimeout = 9.seconds),
                                    "stream did not round-trip after passive source rebind",
                                )
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                            proxy.close()
                        }
                    }
                }
            }
        }

    /**
     * Minimal userspace UDP forwarder that simulates a NAT rebind. Client ↔ [proxyPort] ↔ server.
     * [rebind] swaps the upstream (server-facing) socket for one with a new source port, so the server
     * sees the same connection arrive from a new 4-tuple. Datagram I/O runs on a shared non-blocking
     * [SelectorDatagramRelay], so neither the rebind nor teardown can hit the `IOException: Success`
     * close-while-blocked-read race (test-only; ByteBuffer is fine in tests).
     */
    private class RebindingUdpProxy(
        serverPort: Int,
    ) {
        // lateinit + init{}: the pass-through callbacks reference the relay, so an inferred `val` whose
        // type comes from those same callbacks would be a type-inference cycle.
        private lateinit var relay: SelectorDatagramRelay

        val proxyPort: Int get() = relay.proxyPort

        init {
            relay =
                SelectorDatagramRelay(
                    serverPort = serverPort,
                    maxDatagram = 2048,
                    onClientToServer = { buf, _ -> relay.writeToServer(buf) },
                    onServerToClient = { buf, _ -> relay.writeToClient(buf) },
                )
            relay.start()
        }

        /** Swap the upstream socket for a fresh source port — the NAT rebind. */
        fun rebind() = relay.rebindUpstream()

        fun close() = relay.close()
    }
}
