package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
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

    /** The client-vantage qlogs in [dir], by name: a server's accepted connection writes its own beside them. */
    private fun clientQlogs(dir: File): Map<String, String> =
        dir
            .listFiles { f -> f.name.endsWith(".sqlog") }
            .orEmpty()
            .associate { it.name to it.readText() }
            .filterValues { CLIENT_VANTAGE in it }

    @Test
    fun twoConsecutiveClientConnectionsWriteTwoQlogs() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QlogPerConnectionTests::class) {
                val dir = Files.createTempDirectory("qlog-621").toFile()
                val sessions =
                    withQlogDir(dir) {
                        withTimeout(60.seconds) {
                            withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                                val accepting = launch(Dispatchers.IO) { connections { } }
                                try {
                                    // Sequential on purpose: the second connect happens after the first
                                    // connection's quiche_conn has been freed, the walk's reconnect shape.
                                    List(2) { withQuicConnection("localhost", port, options, timeout = 10.seconds) { identity.session.hex } }
                                } finally {
                                    accepting.cancel()
                                }
                            }
                        }
                    }
                assertEquals(2, sessions.toSet().size, "two connections carry two session ids: $sessions")

                val qlogs = clientQlogs(dir)
                assertEquals(
                    2,
                    qlogs.size,
                    "one client qlog per connection, got ${qlogs.keys} for sessions $sessions in ${dir.listFiles()?.map { it.name }}",
                )
                for (session in sessions) {
                    val own = qlogs.filterValues { "\"scid\":\"$session\"" in it }
                    assertEquals(1, own.size, "exactly one client qlog carries connection $session's Initial: ${own.keys} of ${qlogs.keys}")
                    val (name, body) = own.entries.single()
                    assertTrue(PARAMETERS_SET in body, "$name has connection $session's parameters_set")
                }
            }
        }

    private companion object {
        const val QLOG_DIR_PROPERTY = "quic.qlog.dir"
        const val CLIENT_VANTAGE = "\"vantage_point\":{\"type\":\"client\"}"
        const val PARAMETERS_SET = "quic:parameters_set"
    }
}
