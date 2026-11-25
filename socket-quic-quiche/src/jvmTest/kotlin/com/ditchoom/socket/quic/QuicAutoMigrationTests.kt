package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end **automatic** connection migration driven by a [com.ditchoom.socket.NetworkMonitor]
 * ([QuicOptions.migration] = [MigrationPolicy.Automatic], the default), over loopback.
 *
 * Unlike [QuicMigrationLoopbackTests] — which calls [QuicScope.migrate] by hand — this test never
 * touches `migrate()`. The client connects with a scriptable [SimNetworkMonitor] injected via
 * [QuicOptions.networkMonitor], whose baseline link is "Wi-Fi". After the first echo we flip the monitor's
 * [NetworkId] to a different link ("cellular"), simulating a handoff. The `wireAutoMigration` reactor
 * observes the `networkId` change and issues `migrate(MigrationTarget.FreshLocalEndpoint)` on its own —
 * re-binding to a fresh
 * ephemeral loopback socket (a distinct 4-tuple, so quiche runs full PATH_CHALLENGE validation,
 * exactly the must-run case in [QuicMigrationLoopbackTests]).
 *
 * We assert the connection reaches [QuicPathState.Migrated] with no manual migrate call, and that the
 * stream still round-trips afterwards — proving the monitor→migration wiring works end to end.
 *
 * Runs in CI (needs the built quiche native lib); skips cleanly without one.
 */
class QuicAutoMigrationTests {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /** Writes [payload] and drains exactly its UTF-8 length back (see [QuicMigrationLoopbackTests.echoOnce]). */
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
            if (resp !is ScopedReadResult.Data) return "no_data"
            got += resp.value
        }
        return acc.decodeToString()
    }

    /**
     * What a failure of [networkIdChangeAutoMigratesTheConnection] needs to say which cause it was: the client's
     * connection and path states as they changed, quiche's spare destination ids and path table sampled while
     * the connection lived, and the trace's migration attempts with their outcomes. Held in memory and written
     * out only when the test fails.
     */
    private class FailureCapture {
        private val lines = ConcurrentLinkedQueue<String>()
        private val started = System.nanoTime()

        fun note(what: String) {
            lines += "+${(System.nanoTime() - started) / 1_000_000}ms $what"
        }

        fun dump(failure: Throwable) {
            System.err.println(
                "QuicAutoMigrationTests failure capture for $failure\n" + lines.joinToString("\n") { "  $it" },
            )
        }
    }

    /** Notes this connection's state and path state as they change, and quiche's spare ids and paths each second. */
    private fun QuicScope.capturedInto(capture: FailureCapture) {
        (this as? QuicScope)?.let { connection -> launch { connection.state.collect { capture.note("client state $it") } } }
        launch { pathState.collect { capture.note("client path $it") } }
        val driver = (this as? QuicheBackedConnection)?.quicheDriver ?: return capture.note("no driver behind $this")
        launch {
            while (true) {
                val sample =
                    driver.inspect { api, conn ->
                        val paths = api.connStats(conn)?.pathsCount ?: 0L
                        "spareDcids=${api.connAvailableDcids(conn)} paths=" +
                            (0 until paths).joinToString(" ") { i ->
                                api.connPathStats(conn, i).let { "[$i state=${it?.validationState} active=${it?.active}]" }
                            }
                    }
                capture.note(
                    when (sample) {
                        is Inspected.Read -> "quiche ${sample.value}"
                        Inspected.ConnectionGone -> "quiche connection gone"
                    },
                )
                delay(1.seconds)
            }
        }
    }

    @Test
    fun networkIdChangeAutoMigratesTheConnection() =
        runBlocking(Dispatchers.IO) {
            val capture = FailureCapture()
            try {
                withTimeout(20.seconds) {
                    // Baseline link the client "connected on" — Wi-Fi. The reactor drops this as the
                    // connect-time baseline; only a *later* distinct link triggers migration.
                    val monitor =
                        SimNetworkMonitor.on(NetworkId.Link(NetworkKind.Wifi, 1L))
                    val clientOptions =
                        QuicOptions(
                            alpnProtocols = listOf("test"),
                            verifyPeer = false,
                            idleTimeout = 10.seconds,
                            // QuicOptions.migration defaults to Automatic; inject the scriptable monitor.
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                            // Datagram lines are most of a trace and say nothing of why a migration did not happen.
                            trace = QuicTraceCapture(sink = { e -> e.toString().let { if (" DGRAM_" !in it) capture.note("trace $it") } }),
                        )
                    val serverOptions =
                        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = serverOptions) {
                        // Echo loop: mirror every message back until the stream ends.
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        // Echo inside the read scope: write is zero-copy and takes no
                                        // ownership, the scope releases the read buffer on exit (#538).
                                        if (stream.read(8.seconds) { stream.write(it, 5.seconds) } !is ScopedReadResult.Data) break
                                    }
                                    stream.close()
                                }
                            }

                        val beforeEcho = CompletableDeferred<String>()
                        val settledPathState = CompletableDeferred<QuicPathState>()
                        val afterEcho = CompletableDeferred<String>()

                        val clientJob =
                            launch(Dispatchers.IO) {
                                // Connect on 127.0.0.1 so the peer is reachable from the migrated path too.
                                withQuicConnection("127.0.0.1", port, clientOptions, timeout = 10.seconds) {
                                    capturedInto(capture)
                                    val stream = openStream()
                                    beforeEcho.complete(stream.echoOnce("before"))

                                    // Wait for the auto-migration reactor to complete (or fail) the switch —
                                    // no manual migrate() call here; the monitor flip below drives it.
                                    val settled =
                                        pathState.first { it is QuicPathState.Migrated || it is QuicPathState.Failed }
                                    settledPathState.complete(settled)

                                    afterEcho.complete(stream.echoOnce("after"))
                                    stream.close()
                                }
                            }

                        try {
                            assertEquals("before", withTimeout(12.seconds) { beforeEcho.await() })

                            // Simulate a Wi-Fi → cellular handoff. This is the ONLY migration trigger —
                            // wireAutoMigration observes the networkId change and migrates the connection.
                            capture.note("monitor flips to cellular")
                            monitor.setNetworkId(NetworkId.Link(NetworkKind.Cellular, 2L))

                            // Note the budget: unchanged from before this phase. The reactor applies no
                            // quiet period, so a genuine handoff is never delayed — if this test needs a
                            // longer wait, a timer crept back into the migration path.
                            val settled = withTimeout(12.seconds) { settledPathState.await() }
                            assertTrue(
                                settled is QuicPathState.Migrated,
                                "network-change did not auto-migrate the connection, settled on $settled",
                            )
                            assertEquals(
                                "after",
                                withTimeout(12.seconds) { afterEcho.await() },
                                "stream did not round-trip after auto-migration",
                            )
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(QuicAutoMigrationTests::class, e)
            } catch (e: Throwable) {
                capture.dump(e)
                throw e
            }
        }
}
