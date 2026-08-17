package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            val resp = read(5.seconds)
            if (resp !is ReadResult.Data) return "no_data"
            val take = minOf(resp.buffer.remaining(), expected.size - got)
            for (j in 0 until take) acc[got + j] = resp.buffer.readByte()
            got += take
        }
        return acc.decodeToString()
    }

    @Test
    fun networkIdChangeAutoMigratesTheConnection() =
        runBlocking(Dispatchers.IO) {
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

                        val beforeEcho = CompletableDeferred<String>()
                        val settledPathState = CompletableDeferred<QuicPathState>()
                        val afterEcho = CompletableDeferred<String>()

                        val clientJob =
                            launch(Dispatchers.IO) {
                                // Connect on 127.0.0.1 so the peer is reachable from the migrated path too.
                                withQuicConnection("127.0.0.1", port, clientOptions, timeout = 10.seconds) {
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
            }
        }
}
