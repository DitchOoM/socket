package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end active connection migration over loopback (slice 3b harness).
 *
 * The client connects on 127.0.0.1, then [QuicScope.migrate]s to a new local path.
 * The driver opens a second socket bound to that path, probes it, and on validation
 * switches the active path. We assert migration succeeds and that the stream still
 * round-trips afterwards — proving streams survive the path switch.
 *
 * Two variants, differing only in the new local path:
 *  - [streamSurvivesActiveMigrationToNewLocalPort] migrates to a fresh ephemeral
 *    port on 127.0.0.1 — a distinct 4-tuple, so quiche runs full path validation.
 *    Needs no privileged setup, so it runs everywhere (the must-run case).
 *  - [streamSurvivesActiveMigrationToLoopbackAlias] migrates to 127.0.0.2, adding
 *    address-change coverage. Free on Linux (all 127.0.0.0/8 is loopback); needs a
 *    privileged `ifconfig lo0 alias` on BSD/macOS, so it skips cleanly there.
 *
 * Runs in CI (needs the built quiche native lib); skips cleanly without a native lib.
 *
 * Exercises the full two-party path-validation handshake: the in-repo echo server feeds quiche
 * the real per-datagram source as recvInfo.from (so a migrated client's new address is seen as a
 * new path) and sends replies to sendInfo.to (so they follow the peer). See JvmQuicServer's
 * per-source recv_info cache and NioUdpChannel's sendInfo.to routing.
 */
class QuicMigrationLoopbackTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    // When QUIC_MIGRATION_REQUIRE_RUN is set (CI on platforms that *must* be able
    // to run this — e.g. Linux, where the native is built fresh and 127.0.0.2 is
    // bindable), an otherwise-clean skip becomes a hard failure. Otherwise a silent
    // skip on a broken native or unbindable alias reads as a green pass, which is
    // exactly how the FFM migration crash went unnoticed. Elsewhere (macOS without
    // a loopback alias, etc.) the skip stays a skip.
    private val requireRun: Boolean =
        System.getenv("QUIC_MIGRATION_REQUIRE_RUN")?.lowercase() in setOf("1", "true", "yes")

    /**
     * Report [reason] through the shared skip path, then apply this suite's own escalation.
     *
     * Two gates, deliberately: [recordSkip] emits the greppable marker the CI inventory counts and
     * fails the lanes that set `SOCKET_REQUIRE_ALL_TESTS` — without it this suite's skips were the one
     * quiche jvmTest hole in that inventory, invisible on exactly the lanes that forbid skipping — and
     * `QUIC_MIGRATION_REQUIRE_RUN` then escalates on the narrower set of lanes that must run *this*
     * test specifically. Whichever fires first, the run goes red naming the reason.
     */
    private fun skipOrFail(reason: SkipReason): Nothing {
        recordSkip(QuicMigrationLoopbackTests::class, reason)
        if (requireRun) {
            kotlin.test.fail("QUIC_MIGRATION_REQUIRE_RUN set but test could not run: ${reason.detail}")
        }
        assumeTrue(reason.detail, false)
        error("unreachable") // assumeTrue(false) aborts via AssumptionViolatedException
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            skipOrFail(SkipReason.NativeLibraryUnavailable(e.message ?: "UnsatisfiedLinkError with no message"))
        }
    }

    /**
     * Verify 127.0.0.2 is bindable (Linux: yes by default; macOS/BSD needs a
     * privileged `ifconfig lo0 alias` that we don't require). This is a plain
     * skip that QUIC_MIGRATION_REQUIRE_RUN does NOT escalate: the alias test is
     * supplemental IP-change coverage, while [streamSurvivesActiveMigrationToNewLocalPort]
     * carries the unprivileged must-run migration guarantee on every platform.
     */
    private fun assumeLoopbackAliasBindable() {
        val bindable =
            try {
                java.nio.channels.DatagramChannel
                    .open()
                    .use { it.bind(InetSocketAddress("127.0.0.2", 0)) }
                true
            } catch (e: Exception) {
                false
            }
        if (!bindable) {
            // Reported, not escalated: see the docstring. It still has to be *visible*, because a host
            // that quietly stopped being able to bind the alias looks exactly like one that never could.
            //
            // The gate has to say so. Recording this under the default lane gate made every
            // SOCKET_REQUIRE_ALL_TESTS=1 macOS lane fail here — a promise the lane cannot keep, since
            // the alias needs a privileged `ifconfig` no lane setting installs. Escalation for this one
            // stays with QUIC_MIGRATION_REQUIRE_RUN below, which is set only where 127.0.0.2 *is*
            // bindable and where a sudden un-bindability is therefore a real regression.
            recordSkip(
                QuicMigrationLoopbackTests::class,
                SkipReason.HostBehaviourDiffers(
                    "loopback alias 127.0.0.2 is not bindable on this host; binding it needs a privileged " +
                        "`ifconfig lo0 alias` on macOS/BSD, which this suite does not require",
                ),
                SkipGate.HostCannotProvideIt("a bindable 127.0.0.2 loopback alias (privileged `ifconfig lo0 alias` on macOS/BSD)"),
            )
            assumeTrue("Loopback alias 127.0.0.2 not bindable on this host (needs a privileged alias)", false)
        }
    }

    /**
     * Writes [payload] and reads its echo back.
     *
     * A QUIC stream is an **unframed byte stream**: a single [read] may return a partial
     * chunk (under-read) or — during an active migration, where extra path-validation
     * packets churn the receive path — more bytes than were sent (over-read of a reused
     * buffer). So we drain to *exactly* the expected UTF-8 length and decode only those
     * bytes. This makes the round-trip assertion deterministic: a genuine data corruption
     * surfaces as a clear `assertEquals` mismatch rather than a flaky
     * `MalformedInputException` from decoding trailing garbage. Any bytes beyond the
     * expected length are left in the stream (which is closed right after).
     */
    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val expected = payload.encodeToByteArray()
        val out = BufferFactory.Default.allocate(expected.size)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)

        val acc = ByteArray(expected.size)
        var got = 0
        while (got < expected.size) {
            // Scoped read (#538): the bytes are copied out inside the block, so the buffer goes back
            // to the driver's pool on the way out instead of waiting for a collector that never runs.
            val resp =
                read(5.seconds) { chunk ->
                    val take = minOf(chunk.remaining(), expected.size - got)
                    for (j in 0 until take) acc[got + j] = chunk.readByte()
                    take
                }
            if (resp !is ScopedRead.Data) return "no_data"
            got += resp.value
        }
        return acc.decodeToString()
    }

    @Test
    fun streamSurvivesActiveMigrationToNewLocalPort() =
        runBlocking(Dispatchers.IO) {
            // Migrating to a fresh ephemeral source port on 127.0.0.1 is a distinct
            // 4-tuple (PathKey includes the port — see SockAddrDecodeTest), so quiche
            // runs the full PATH_CHALLENGE/RESPONSE validation exactly as it would for
            // a new IP. Unlike the 127.0.0.2 variant this needs no loopback alias, so
            // it runs unprivileged on every platform — including BSD/macOS, where only
            // 127.0.0.1 is bound by default. This is the path #70 wanted exercised on
            // BSD layout (sendInfoToAddr/decodePathKey during a real flush).
            migrationTest(MigrationTarget.LocalAddress("127.0.0.1"))
        }

    @Test
    fun streamSurvivesActiveMigrationToLoopbackAlias() =
        runBlocking(Dispatchers.IO) {
            assumeLoopbackAliasBindable()
            migrationTest(MigrationTarget.LocalAddress("127.0.0.2"))
        }

    private suspend fun migrationTest(target: MigrationTarget.LocalAddress) {
        skipOnMissingNativeLib {
            withTimeout(20.seconds) {
                withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                    // Echo loop: mirror every message back until the stream ends.
                    val serverJob =
                        launch(Dispatchers.IO) {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    if (stream.read(8.seconds) { stream.write(it, 5.seconds) } !is ScopedRead.Data) break
                                }
                                stream.close()
                            }
                        }

                    val migrationResult = CompletableDeferred<MigrationResult>()
                    val beforeEcho = CompletableDeferred<String>()
                    val afterEcho = CompletableDeferred<String>()

                    val clientJob =
                        launch(Dispatchers.IO) {
                            // Connect on 127.0.0.1 so the peer is reachable from the migrated path too.
                            withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                beforeEcho.complete(stream.echoOnce("before"))

                                // Active migration to a new local source (different port and/or address).
                                val result = migrate(target)
                                migrationResult.complete(result)

                                afterEcho.complete(stream.echoOnce("after"))
                                stream.close()
                            }
                        }

                    try {
                        assertEquals("before", withTimeout(12.seconds) { beforeEcho.await() })
                        val result = withTimeout(12.seconds) { migrationResult.await() }
                        assertTrue(
                            result is MigrationResult.Succeeded,
                            "expected migration to succeed, got $result",
                        )
                        // The RESOLVED endpoint, not the request: the target named an address but left
                        // the port ephemeral, so a Succeeded that echoed its request would report port 0.
                        assertEquals(target.host, result.localEndpoint.host, "migration bound a different address")
                        assertTrue(
                            result.localEndpoint.port in 1..65535,
                            "migration must report the ephemeral port it bound, got ${result.localEndpoint}",
                        )
                        assertEquals(
                            "after",
                            withTimeout(12.seconds) { afterEcho.await() },
                            "stream did not round-trip after migration",
                        )
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }
    }

    /**
     * Deterministic read-path guard (runs under whichever quiche backend CI selects —
     * JNI and FFM). Echoes payloads across frame/packet-boundary sizes, filled with
     * high bytes (0x80..0xFF), and asserts each round-trips **byte-exact**. Two ways a
     * corrupt read is caught with no reliance on timing:
     *  - [assertContentEquals] fails on any wrong/garbage byte, and
     *  - [roundTripExact] fails if the read delivering the final expected byte still has
     *    leftover bytes — i.e. an **over-read** (the class of defect the FFM migration
     *    flake's `MalformedInputException` pointed at, but on the plain read path).
     */
    @Test
    fun streamRoundTripsExactBytesAcrossSizesAndBytePatterns() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        // Echo inside the read scope: write is zero-copy and takes no
                                        // ownership, the scope releases the read buffer on exit (#538).
                                        if (stream.read(8.seconds) { stream.write(it, 5.seconds) } !is ScopedRead.Data) break
                                    }
                                    stream.close()
                                }
                            }

                        val done = CompletableDeferred<Unit>()
                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    for (size in listOf(1, 5, 63, 64, 255, 1024, 4096)) {
                                        stream.roundTripExact(ByteArray(size) { ((it * 31 + 0x80) and 0xFF).toByte() })
                                    }
                                    stream.close()
                                    done.complete(Unit)
                                }
                            }

                        try {
                            withTimeout(28.seconds) { done.await() }
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /** Writes [payload], drains exactly [payload].size bytes, and asserts a byte-exact echo. */
    private suspend fun QuicByteStream.roundTripExact(payload: ByteArray) {
        val out = BufferFactory.Default.allocate(payload.size)
        for (b in payload) out.writeByte(b)
        out.resetForRead()
        write(out, 5.seconds)

        val acc = ByteArray(payload.size)
        var got = 0
        while (got < payload.size) {
            val resp =
                read(5.seconds) { chunk ->
                    val take = minOf(chunk.remaining(), payload.size - got)
                    for (j in 0 until take) acc[got + j] = chunk.readByte()
                    take to chunk.remaining()
                }
            assertTrue(resp is ScopedRead.Data, "stream ended early after $got/${payload.size} bytes")
            val (take, leftOver) = resp.value
            got += take
            if (got == payload.size && leftOver > 0) {
                fail("over-read: $leftOver unexpected byte(s) past ${payload.size} in one read")
            }
        }
        assertContentEquals(payload, acc, "echoed bytes differ from sent (size=${payload.size})")
    }
}
