package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Every connection a process opens writes its own qlog (#621).
 *
 * quiche opens a qlog with `create_new`, so a second connection whose file name repeats the first's
 * is refused silently. A name derived from the native handle repeats as soon as the allocator hands
 * the freed `quiche_conn`'s address to the next one — which is why a 75 h iOS walk of 14 connections
 * pulled exactly one `.sqlog`, ending the millisecond connection 1 died.
 */
class QlogPerConnectionTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url = QlogPerConnectionTests::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /** The JVM's `QUIC_QLOG_DIR` seam: a process cannot set its own environment, the property is read first. */
    private inline fun <T> withQlogDir(
        dir: File,
        block: () -> T,
    ): T {
        val previous = System.getProperty(QLOG_DIR_PROPERTY)
        System.setProperty(QLOG_DIR_PROPERTY, dir.absolutePath)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(QLOG_DIR_PROPERTY) else System.setProperty(QLOG_DIR_PROPERTY, previous)
        }
    }

    /**
     * Two client connections, one after the other — the second connects after the first's
     * `quiche_conn` has been freed, the shape of every reconnect on a walk. Returns their session ids.
     */
    private suspend fun twoConsecutiveConnections(clientOptions: QuicOptions): List<String> =
        withTimeout(60.seconds) {
            withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                val accepting = launch(Dispatchers.IO) { connections { } }
                try {
                    List(2) { withQuicConnection("localhost", port, clientOptions, timeout = 10.seconds) { identity.session.hex } }
                } finally {
                    accepting.cancel()
                }
            }
        }

    /** The client-vantage qlogs in [dir], by name: a server's accepted connection writes its own beside them. */
    private fun clientQlogs(dir: File): Map<String, String> =
        dir
            .listFiles { f -> f.name.endsWith(".sqlog") }
            .orEmpty()
            .associate { it.name to it.readText() }
            .filterValues { CLIENT_VANTAGE in it }

    /** The one client qlog that carries [session]'s Initial, with its `parameters_set`; returns its file name. */
    private fun qlogOf(
        qlogs: Map<String, String>,
        session: String,
    ): String {
        val own = qlogs.filterValues { "\"scid\":\"$session\"" in it }
        assertEquals(1, own.size, "exactly one client qlog carries connection $session's Initial: ${own.keys} of ${qlogs.keys}")
        val (name, body) = own.entries.single()
        assertTrue(PARAMETERS_SET in body, "$name has connection $session's parameters_set")
        return name
    }

    @Test
    fun theEnvironmentDoorWritesOneQlogPerConnectionNamedBySession() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QlogPerConnectionTests::class) {
                val dir = Files.createTempDirectory("qlog-621-env").toFile()
                val sessions = withQlogDir(dir) { twoConsecutiveConnections(options) }
                assertEquals(2, sessions.toSet().size, "two connections carry two session ids: $sessions")

                val qlogs = clientQlogs(dir)
                assertEquals(2, qlogs.size, "one client qlog per connection, got ${qlogs.keys} for sessions $sessions")
                for (session in sessions) {
                    assertEquals("quiche-client-$session.sqlog", qlogOf(qlogs, session), "named by the session id, never by a handle")
                }
            }
        }

    @Test
    fun aCaptureNamesEachConnectionsQlogBesideItsTrace() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QlogPerConnectionTests::class) {
                val dir = Files.createTempDirectory("qlog-621-capture").toFile()
                // The probes' shape: one sequence number names both records of a connection.
                val connections = AtomicInteger(0)
                val capture =
                    QuicTraceCapture(
                        captureFor = {
                            val name = "conn-" + connections.incrementAndGet().toString().padStart(4, '0')
                            val trace = File(dir, "$name.trace")
                            QuicConnectionCapture(
                                sink = TraceSink { event -> trace.appendText(event.toString() + "\n") },
                                qlog = QlogTarget.File(File(dir, "$name.sqlog").absolutePath),
                            )
                        },
                    )
                val sessions = twoConsecutiveConnections(options.copy(trace = capture))
                assertEquals(2, sessions.toSet().size, "two connections carry two session ids: $sessions")

                val qlogs = clientQlogs(dir)
                assertEquals(listOf("conn-0001.sqlog", "conn-0002.sqlog"), qlogs.keys.sorted(), "one qlog per connection, paired by name")
                sessions.forEachIndexed { index, session ->
                    val name = "conn-000${index + 1}"
                    assertEquals("$name.sqlog", qlogOf(qlogs, session), "connection ${index + 1}'s qlog carries its own Initial")
                    assertTrue(File(dir, "$name.trace").length() > 0, "$name.trace sits beside $name.sqlog")
                }
            }
        }

    private companion object {
        const val QLOG_DIR_PROPERTY = "quic.qlog.dir"
        const val CLIENT_VANTAGE = "\"vantage_point\":{\"type\":\"client\"}"
        const val PARAMETERS_SET = "quic:parameters_set"
    }
}
