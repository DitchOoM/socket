package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **Does a loopback QUIC handshake ever fail to establish — and when it does, whose datagrams went
 * where?** The instrument that located #450 / #367, kept as the control any future claim about them
 * needs.
 *
 * #450's local sighting was one failure in 25 runs of a 32-test class — about one handshake in 800 —
 * so re-running that suite spends nearly all its wall time on HTTP/3 request/response work the failure
 * has nothing to do with. The reported failure is a *handshake*, so this drives handshakes and nothing
 * else, through the same `withQuicServer` / `withQuicConnection` entry points and with the same 10s
 * idle timeout and 15s establishment bound: about 100 handshakes per second, against roughly 4 per
 * second for the suite.
 *
 * ## What it found
 *
 * Three arms, identical except for whether the server binds a fresh ephemeral UDP port each time.
 * Measured on `main` before the fix, 18-core Mac, 12–16 burner processes, load average ~20:
 *
 * | arm | handshakes | `ByLocal(IdleTimeout)` |
 * |---|---|---|
 * | sequential, one long-lived server | 20 000 | 0 |
 * | concurrent ×8, one long-lived server | 20 000 | 0 |
 * | **fresh server per handshake** | **20 000** | **4** |
 * | **fresh server per handshake (counted)** | **20 000** | **5** |
 *
 * Same client dial, same load, same process: the failure lives in **server bind churn**, not in load,
 * not in the client, not in concurrency. #450's load-dependence was a confounder — the suite that
 * exposed it binds a fresh ephemeral port per test, so a loaded machine simply ran more binds.
 *
 * The cause is in `UdpSocket.bind`: on Darwin a dual-stack wildcard socket does not own the IPv4 half
 * of its port, so `bind(0)` could hand a server a port whose IPv4 half belonged to an unrelated daemon.
 * The same four arms after that fix: **80 000 handshakes, 0 failures.**
 *
 * ## What it records when one fails
 *
 * Both ends' datagrams, the server receive loop's own `headerInfo`/`accept`/`connRecv` counts, a
 * post-mortem probe datagram from an unrelated socket, the JVM's thread stacks, and every socket the
 * OS holds on that port. That chain is what separated "the client never sent" from "the client sent and
 * the server never got it" from "the server went deaf" — the ambiguity #367 was closed on for months.
 *
 * The default iteration count is small enough to belong in the ordinary suite; raise it to hunt:
 *
 * ```
 * HUNT450_HANDSHAKES=20000 ./gradlew :socket-quic-quiche:jvmTest \
 *   --tests 'com.ditchoom.socket.quic.LoopbackHandshakeBindChurnTests' --rerun
 * ```
 */
class LoopbackHandshakeBindChurnTests {
    /** How one handshake attempt ended — exhaustive, so an unfamiliar failure cannot be counted as a pass. */
    private sealed interface Attempt {
        data object Established : Attempt

        /** The #450 signature: our side gave up mid-handshake. */
        data class LocalClose(
            val reason: String,
        ) : Attempt

        /** The #450 CI signature before #499: the caller's bound fired with nothing typed behind it. */
        data object CallerTimeout : Attempt

        data class Threw(
            val kind: String,
            val message: String,
        ) : Attempt
    }

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    // The loopback suite's own transport shape: a 10s idle timeout and a 15s establishment bound.
    private val options =
        QuicOptions(
            alpnProtocols = listOf("hunt450"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /**
     * Handshakes per arm. The default is small enough to belong in the ordinary suite (three arms,
     * well under a second in total) and non-zero on purpose: a hunt instrument that runs nothing by
     * default reports green having measured nothing, which is the loud-skip failure this repository
     * has already been bitten by. Raise it with `HUNT450_HANDSHAKES` when hunting.
     */
    private fun iterations(): Int = System.getenv("HUNT450_HANDSHAKES")?.toIntOrNull() ?: DEFAULT_HANDSHAKES

    private suspend fun dial(
        port: Int,
        clientOptions: QuicOptions = options,
    ): Attempt =
        try {
            withQuicConnection("localhost", port, clientOptions, timeout = 15.seconds) { Attempt.Established }
        } catch (e: QuicCloseException) {
            Attempt.LocalClose(e.closeReason.toString())
        } catch (_: TimeoutCancellationException) {
            Attempt.CallerTimeout
        } catch (t: Throwable) {
            Attempt.Threw(t::class.simpleName ?: "unknown", t.message ?: "")
        }

    private fun report(
        label: String,
        attempts: List<Attempt>,
        elapsedMs: Long,
    ): String =
        buildString {
            val byKind = attempts.groupingBy { if (it is Attempt.Established) "established" else it.toString() }.eachCount()
            appendLine("[#450 bind churn] $label: ${attempts.size} handshakes in ${elapsedMs}ms")
            byKind.entries.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("    $v  $k") }
        }

    /**
     * One server, many sequential clients — the cheapest handshake-per-second arm, and the one that
     * isolates the client dial from server bind/teardown.
     */
    @Test
    fun sequentialHandshakesAgainstOneServer() {
        val n = iterations()
        val attempts = ArrayList<Attempt>(n)
        val ms =
            measureTimeMillis {
                runBlocking(Dispatchers.Default) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                        val serverPort = port
                        val accepting = CoroutineScope(coroutineContext)
                        val acceptJob = accepting.launchAcceptLoop(this)
                        try {
                            repeat(n) { attempts += dial(serverPort) }
                        } finally {
                            acceptJob.cancel()
                        }
                    }
                }
            }
        println(report("sequential, one server", attempts, ms))
        assertEquals(
            n,
            attempts.count {
                it is Attempt.Established
            },
            "handshakes that did not establish: ${attempts.filter { it !is Attempt.Established }}",
        )
    }

    /**
     * What one handshake attempt put on the wire, from both ends — kept per attempt so a failure at
     * 1-in-thousands can be read without re-running anything.
     */
    private class WireLog {
        val clientOut = ArrayList<String>()
        val clientIn = ArrayList<String>()
        val serverOut = ArrayList<String>()
        val serverIn = ArrayList<String>()

        fun sink(
            out: MutableList<String>,
            inn: MutableList<String>,
        ) = com.ditchoom.socket.testkit.trace.TraceSink { e ->
            when (e) {
                is TraceEvent.DgramOut -> synchronized(this) { if (out.size < CAP) out += "${e.len}B ${e.path}" }
                is TraceEvent.DgramIn -> synchronized(this) { if (inn.size < CAP) inn += "${e.len}B ${e.path}" }
                else -> Unit
            }
        }

        override fun toString() =
            "client OUT=${clientOut.size} $clientOut IN=${clientIn.size} $clientIn | " +
                "server OUT=${serverOut.size} $serverOut IN=${serverIn.size} $serverIn"

        private companion object {
            const val CAP = 12
        }
    }

    /**
     * A fresh server per iteration — the shape `Http3LoopbackTestSuite` actually runs, where every test
     * binds its own ephemeral port and dials it once.
     *
     * **This is the arm that reproduces #450.** Measured on `main` (1733ff16), 18-core Mac, 16 burner
     * processes, load average ~20:
     *
     * | arm | handshakes | `ByLocal(IdleTimeout)` |
     * |---|---|---|
     * | sequential, one long-lived server | 20 000 | 0 |
     * | concurrent ×8, one long-lived server | 20 000 | 0 |
     * | **fresh server per handshake** | **20 000** | **4** |
     *
     * Same client dial, same load, same process — the only variable is whether the server binds a new
     * ephemeral UDP port for each attempt. So the failure lives in **server bind/teardown churn**, not
     * in load, not in the client's dial, and not in concurrency; and #450's load-dependence is a
     * confounder, because the suite that exposed it binds a fresh server per test.
     *
     * Both ends' datagrams are captured per attempt so the *next* reproduction says which of the two
     * readings of an empty server trace applies (#367): the client sent and nothing arrived, or the
     * client never sent.
     */
    @Test
    fun freshServerPerHandshake() {
        val n = iterations()
        val attempts = ArrayList<Attempt>(n)
        val failures = ArrayList<String>()
        val recentPorts = ArrayList<Int>()
        val ms =
            measureTimeMillis {
                runBlocking(Dispatchers.Default) {
                    repeat(n) { i ->
                        val wire = WireLog()
                        val serverOptions = options.copy(trace = QuicTraceCapture(wire.sink(wire.serverOut, wire.serverIn)))
                        val clientOptions = options.copy(trace = QuicTraceCapture(wire.sink(wire.clientOut, wire.clientIn)))
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = serverOptions) {
                            val serverPort = port
                            recentPorts += serverPort
                            val accepting = CoroutineScope(coroutineContext)
                            val acceptJob = accepting.launchAcceptLoop(this)
                            try {
                                val attempt = dial(serverPort, clientOptions)
                                attempts += attempt
                                if (attempt !is Attempt.Established) {
                                    val window = recentPorts.takeLast(HISTORY)
                                    failures +=
                                        "attempt #$i serverPort=$serverPort outcome=$attempt\n" +
                                        "    wire: $wire\n" +
                                        "    server ports of the last ${window.size} attempts: $window"
                                }
                            } finally {
                                acceptJob.cancel()
                            }
                        }
                    }
                }
            }
        println(report("fresh server per handshake", attempts, ms))
        failures.forEach { println("[#450 bind churn] $it") }
        assertEquals(
            n,
            attempts.count { it is Attempt.Established },
            "handshakes that did not establish:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * Concurrent dials against one server. Contention on the receive loop and on ephemeral-port
     * assignment is the part of the field conditions that a sequential loop cannot reproduce.
     */
    @Test
    fun concurrentHandshakesAgainstOneServer() {
        val n = iterations()
        val fanout = System.getenv("HUNT450_FANOUT")?.toIntOrNull() ?: 8
        val attempts = ArrayList<Attempt>(n)
        val ms =
            measureTimeMillis {
                runBlocking(Dispatchers.Default) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                        val serverPort = port
                        val accepting = CoroutineScope(coroutineContext)
                        val acceptJob = accepting.launchAcceptLoop(this)
                        try {
                            var done = 0
                            while (done < n) {
                                val batch = minOf(fanout, n - done)
                                withContext(Dispatchers.Default) {
                                    attempts += (1..batch).map { async { dial(serverPort) } }.awaitAll()
                                }
                                done += batch
                            }
                        } finally {
                            acceptJob.cancel()
                        }
                    }
                }
            }
        println(report("concurrent x$fanout, one server", attempts, ms))
        assertEquals(
            n,
            attempts.count {
                it is Attempt.Established
            },
            "handshakes that did not establish: ${attempts.filter { it !is Attempt.Established }}",
        )
    }

    private companion object {
        const val DEFAULT_HANDSHAKES = 25

        /** How many recent server ports a failure report carries, to show an ephemeral-port reuse. */
        const val HISTORY = 32
    }

    /**
     * A [QuicheApi] spy that counts what the **server's receive loop** actually did with the bytes the
     * kernel handed it: one `headerInfo` per datagram it dequeued, one `accept` per connection it
     * created, one `connRecv` per packet it fed to quiche.
     *
     * This is the measurement `server QUIC trace (0 most recent events)` cannot make (#367). That trace
     * is per *accepted connection*, so it is empty both when the datagram never arrived and when it
     * arrived and was rejected before a connection existed — two readings with different fixes. A
     * `headerInfo` count separates them at the only place both readings agree on: the receive loop.
     */
    private class ReceiveLoopCounters(
        private val delegate: QuicheApi,
    ) : QuicheApi by delegate {
        @Volatile
        var headerInfoCalls = 0
            private set

        @Volatile
        var acceptCalls = 0
            private set

        @Volatile
        var connRecvCalls = 0
            private set

        override fun headerInfo(
            buf: Long,
            bufLen: Int,
            dcil: Int,
            versionOut: Long,
            typeOut: Long,
            scidOut: Long,
            scidLenOut: Long,
            dcidOut: Long,
            dcidLenOut: Long,
            tokenOut: Long,
            tokenLenOut: Long,
        ): Int {
            headerInfoCalls++
            return delegate.headerInfo(
                buf,
                bufLen,
                dcil,
                versionOut,
                typeOut,
                scidOut,
                scidLenOut,
                dcidOut,
                dcidLenOut,
                tokenOut,
                tokenLenOut,
            )
        }

        override fun accept(
            scidAddr: Long,
            scidLen: Int,
            odcidAddr: Long,
            odcidLen: Int,
            localAddr: Long,
            localLen: Int,
            peerAddr: Long,
            peerLen: Int,
            config: QuicheConfig,
        ): QuicheConn {
            acceptCalls++
            return delegate.accept(scidAddr, scidLen, odcidAddr, odcidLen, localAddr, localLen, peerAddr, peerLen, config)
        }

        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int {
            connRecvCalls++
            return delegate.connRecv(conn, buf, bufLen, recvInfo)
        }

        override fun toString() = "headerInfo=$headerInfoCalls accept=$acceptCalls connRecv=$connRecvCalls"
    }

    /**
     * **The targeted assertion: for a handshake that fails, were the client's datagrams handed to the
     * server's receive loop at all?**
     *
     * Same fresh-server-per-handshake arm as [freshServerPerHandshake] — the one that reproduces — but
     * with the server built through [buildJvmQuicServer] so a [ReceiveLoopCounters] spy sits under it.
     * On a failure the report carries the client's `DGRAM_OUT` count beside the server's `headerInfo`
     * count, which names the layer: equal counts put the fault above the socket (parse / accept /
     * routing), a client count with a zero server count puts it at or below the socket (the datagram
     * left the client and never reached the server's socket).
     */
    @Test
    fun freshServerPerHandshake_countsDatagramsAtTheServersReceiveLoop() {
        val n = iterations()
        val attempts = ArrayList<Attempt>(n)
        val failures = ArrayList<String>()
        val ms =
            measureTimeMillis {
                runBlocking(Dispatchers.Default) {
                    repeat(n) { i ->
                        val wire = WireLog()
                        val counters = ReceiveLoopCounters(loadQuicheApi())
                        val clientOptions = options.copy(trace = QuicTraceCapture(wire.sink(wire.clientOut, wire.clientIn)))
                        val server =
                            buildJvmQuicServer(
                                binding = QuicPortBinding.Own(0, null),
                                tlsConfig = tlsConfig,
                                requestedOptions = options.copy(trace = QuicTraceCapture(wire.sink(wire.serverOut, wire.serverIn))),
                                api = counters,
                            )
                        try {
                            val serverPort = server.port
                            val accepting = CoroutineScope(coroutineContext)
                            val acceptJob = accepting.launchAcceptLoop(server)
                            try {
                                val attempt = dial(serverPort, clientOptions)
                                attempts += attempt
                                if (attempt !is Attempt.Established) {
                                    // The discriminator. `headerInfo == 0` has two readings — the
                                    // datagrams never reached this socket, or this server stopped
                                    // reading it — and only a fresh datagram from outside the
                                    // connection tells them apart.
                                    val before = counters.headerInfoCalls
                                    val threads = jvmThreadInventory()
                                    val sockets = socketsOnPort(serverPort)
                                    val probe = probeServerSocket(serverPort)
                                    failures +=
                                        "attempt #$i serverPort=$serverPort outcome=$attempt\n" +
                                        "    client sent ${wire.clientOut.size} datagram(s); server receive loop saw $counters\n" +
                                        "    post-mortem probe: $probe; headerInfo $before -> ${counters.headerInfoCalls} " +
                                        "(a rise means the socket and its receive loop are ALIVE and the client's " +
                                        "datagrams went somewhere else; no rise means this server went deaf)\n" +
                                        "    threads: $threads\n" +
                                        "    sockets on udp/$serverPort:\n$sockets\n" +
                                        "    wire: $wire"
                                }
                            } finally {
                                acceptJob.cancel()
                            }
                        } finally {
                            server.close()
                        }
                    }
                }
            }
        println(report("fresh server per handshake (counted)", attempts, ms))
        failures.forEach { println("[#450 bind churn] $it") }
        assertEquals(
            n,
            attempts.count { it is Attempt.Established },
            "handshakes that did not establish:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * Every socket in this machine that holds [port] on UDP, with its local address, straight from the
     * OS. A wildcard-bound server and a socket bound to the *specific* loopback address share a port
     * number but not delivery: on BSD the more specific bind wins every datagram, silently. That is one
     * of the two ways a datagram can leave the client and never reach a server whose `select()` is
     * parked and healthy, so it has to be read from outside the JVM rather than inferred.
     */
    private fun socketsOnPort(port: Int): String =
        try {
            val lsof = ProcessBuilder("/usr/sbin/lsof", "-nP", "-iUDP:$port").redirectErrorStream(true).start()
            val lsofOut = lsof.inputStream.bufferedReader().readText()
            lsof.waitFor()
            // netstat carries what lsof does not: each socket's Recv-Q. A non-zero Recv-Q on the
            // server's socket would mean the datagrams ARE in its buffer and its selector is deaf;
            // zero everywhere means they were delivered somewhere else, or nowhere.
            val netstat = ProcessBuilder("/usr/sbin/netstat", "-an", "-p", "udp").redirectErrorStream(true).start()
            val netstatOut = netstat.inputStream.bufferedReader().readText()
            netstat.waitFor()
            val netstatRows = netstatOut.lines().filter { it.contains(".$port ") || it.endsWith(".$port") }
            (
                lsofOut.lines().filter { it.isNotBlank() }.map { "      lsof    $it" } +
                    netstatRows.map { "      netstat $it" }
            ).joinToString("\n").ifEmpty { "      <no socket holds udp/$port>" }
        } catch (t: Throwable) {
            "      lsof unavailable: ${t::class.simpleName}: ${t.message}"
        }

    /**
     * Send one junk datagram at [serverPort] from a socket that has nothing to do with the failed
     * connection, then give the server a beat to dequeue it. The caller reads the receive loop's
     * `headerInfo` counter across this call: it rises if and only if the server's socket is still
     * delivering and its loop is still reading.
     */
    private suspend fun probeServerSocket(serverPort: Int): String =
        try {
            val probe =
                com.ditchoom.socket.udp.UdpSocket.connect(
                    remoteHost = "127.0.0.1",
                    remotePort = serverPort,
                    receiveBufferSize = 2048,
                )
            try {
                val payload =
                    com.ditchoom.buffer.BufferFactory
                        .network()
                        .allocate(32)
                repeat(32) { payload.writeByte(0x2a.toByte()) }
                payload.resetForRead()
                probe.send(payload)
                kotlinx.coroutines.delay(500)
                "sent 32B to 127.0.0.1:$serverPort"
            } finally {
                probe.close()
            }
        } catch (t: Throwable) {
            "probe FAILED: ${t::class.simpleName}: ${t.message}"
        }

    /** Accept every connection and return immediately — the handshake is the whole subject here. */
    private fun CoroutineScope.launchAcceptLoop(server: QuicServer) =
        launch {
            runCatching { server.connections { } }
        }
}
