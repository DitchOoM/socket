package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * quiche is single-threaded, and each connection's driver loop owns its `quiche_conn`: once a loop has
 * started driving a connection, every call on it must come from that loop. A call from any other
 * thread is a data race with the loop — and, after `connFree`, a use-after-free.
 *
 * [LoopConfinement] wraps the real backend and marks the loop through the drivers' own
 * `driverContext`, so a call is attributed to the loop exactly when it runs in a driver coroutine. It
 * records every call on a driven connection that did not, and every call on a freed one. The test
 * exercises the whole caller-visible surface of both ends — identity, state, path, stats — from other
 * threads, continuously, while the connection carries traffic and migrates.
 */
class QuicheLoopConfinementTest {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /** The backend, with every connection call checked against which thread made it. */
    private class LoopConfinement(
        private val backend: QuicheApi,
    ) {
        private val onLoop = ThreadLocal.withInitial { false }

        /** Added to the drivers' `driverContext`: a thread running driver code sees `onLoop` set. */
        val loopMarker = onLoop.asContextElement(true)

        private sealed interface Conn {
            data object Unclaimed : Conn

            data object Driven : Conn

            data object Freed : Conn
        }

        private val conns = ConcurrentHashMap<Long, Conn>()
        val violations = CopyOnWriteArrayList<String>()
        val loopCalls = AtomicLong()

        val api: QuicheApi =
            Proxy.newProxyInstance(QuicheApi::class.java.classLoader, arrayOf(QuicheApi::class.java)) { _, method, args ->
                val name = method.name.substringBefore('-')
                val handle = (args?.firstOrNull() as? Long)?.takeIf { conns.containsKey(it) }
                if (handle != null) check(name, handle)
                val result =
                    try {
                        method.invoke(backend, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                when (name) {
                    // A new connection, possibly at an address a freed one had: owned by whoever built it
                    // until a loop starts driving it.
                    "connect", "accept" -> (result as? Long)?.takeIf { it != 0L }?.let { conns[it] = Conn.Unclaimed }
                    "connFree" -> handle?.let { conns[it] = Conn.Freed }
                }
                result
            } as QuicheApi

        private fun check(
            name: String,
            handle: Long,
        ) {
            val here = onLoop.get()
            if (here) loopCalls.incrementAndGet()
            when (conns.getValue(handle)) {
                Conn.Freed -> violations += violation("after connFree", name)
                Conn.Driven -> if (!here) violations += violation("off the driver loop", name)
                Conn.Unclaimed -> if (here) conns[handle] = Conn.Driven
            }
        }

        private fun violation(
            what: String,
            name: String,
        ): String {
            val caller =
                Thread
                    .currentThread()
                    .stackTrace
                    .drop(2)
                    .firstOrNull { it.className.startsWith("com.ditchoom") && !it.className.contains("LoopConfinement") }
            return "$name $what on ${Thread.currentThread().name} from $caller"
        }
    }

    /** Every caller-visible accessor of a connection, read once. */
    private suspend fun readEverything(connection: QuicConnection) {
        connection.identity
        connection.state.value
        connection.unreadAtClose.value
        connection.pathState.value
        connection.networkAtClose
        connection.capabilities
        connection.sessionTicket.value
        connection.remoteAddress
        when (connection.state.value) {
            is QuicConnectionState.Established -> {
                connection.negotiatedAlpn
                connection.resumption
            }
            else -> Unit
        }
        (connection as QuicheBackedConnection).quicheDriver.let { driver ->
            driver.pathLiveness.value
            driver.stats()
            driver.sourceIds()
        }
    }

    /** [readEverything] in a loop on another thread until cancelled; counts its rounds. */
    private fun CoroutineScope.hammer(
        connection: QuicConnection,
        rounds: AtomicLong,
    ): Job =
        launch(Dispatchers.Default) {
            while (isActive) {
                readEverything(connection)
                rounds.incrementAndGet()
                yield()
            }
        }

    @Test
    fun noConnectionCallLeavesTheDriverLoopWhileCallersReadEverythingThroughTrafficAndAMigration() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicheLoopConfinementTest::class) {
                val confinement = LoopConfinement(loadQuicheApi())
                val tuning = QuicheDriverTuning(driverContext = Dispatchers.Default + confinement.loopMarker)
                val opts = QuicOptions(alpnProtocols = listOf("confined"), verifyPeer = false, migration = MigrationPolicy.Manual)
                val hammers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val clientRounds = AtomicLong()
                val serverRounds = AtomicLong()
                val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, opts, tuning, confinement.api)
                try {
                    val serverJob =
                        launch {
                            server.connections {
                                val serverHammer = hammers.hammer(this as QuicConnection, serverRounds)
                                try {
                                    val stream = acceptStream()
                                    while (true) {
                                        if (stream.read(10.seconds) { stream.writeFully(it, 5.seconds) } !is ScopedRead.Data) break
                                    }
                                    stream.close()
                                } finally {
                                    serverHammer.cancelAndJoin()
                                }
                            }
                        }
                    withTimeout(30.seconds) {
                        commonJvmWithQuicConnection(
                            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), server.port),
                            serverName = "localhost",
                            quicOptions = opts,
                            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            timeout = 20.seconds,
                            api = confinement.api,
                            tuning = tuning,
                        ) {
                            val clientHammer = hammers.hammer(this as QuicConnection, clientRounds)
                            val stream = openStream()
                            repeat(ECHOES) { i -> stream.echo("before-$i") }
                            assertIs<MigrationResult.Succeeded>(migrate(MigrationTarget.LocalAddress("127.0.0.1")))
                            repeat(ECHOES) { i -> stream.echo("after-$i") }
                            stream.shutdownSend()
                            clientHammer.cancelAndJoin()
                        }
                    }
                    serverJob.cancelAndJoin()
                } finally {
                    hammers.cancel()
                    server.close()
                }

                assertTrue(confinement.loopCalls.get() > 0, "no call was ever attributed to a driver loop, so nothing was checked")
                assertTrue(clientRounds.get() > 0 && serverRounds.get() > 0, "the accessors were never read concurrently")
                if (confinement.violations.isNotEmpty()) {
                    fail(
                        "${confinement.violations.size} quiche connection call(s) left the driver loop:\n" +
                            confinement.violations
                                .distinct()
                                .take(20)
                                .joinToString("\n"),
                    )
                }
            }
        }

    private suspend fun QuicByteStream.echo(text: String) {
        val out = BufferFactory.deterministic().allocate(text.length)
        try {
            out.writeString(text, Charset.UTF8)
            out.resetForRead()
            writeFully(out, 5.seconds)
        } finally {
            out.freeIfNeeded()
        }
        val back = StringBuilder()
        while (back.length < text.length) {
            val r = read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
            back.append(assertIs<ScopedRead.Data<String>>(r, "echo of '$text' ended after '$back'").value)
        }
    }

    private companion object {
        const val ECHOES = 20
    }
}
