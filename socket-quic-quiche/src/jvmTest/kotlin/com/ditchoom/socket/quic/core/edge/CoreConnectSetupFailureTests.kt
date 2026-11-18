@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A connect that fails before its connection starts releases everything its setup made, each once: the sockaddrs it
 * encoded, quiche's connection, the scope its observers run in, the socket and the memory, whatever step failed.
 */
class CoreConnectSetupFailureTests {
    private val loops = LoopSet(1)
    private val qlogDir: File = Files.createTempDirectory("core-setup-failure").toFile()

    @AfterTest
    fun cleanUp() {
        loops.close()
        qlogDir.deleteRecursively()
    }

    @Test
    fun aConnectQuicheRefusesFreesTheSockaddrsAndArgumentsItEncodedOnce() =
        runBlocking {
            val rig = Rig()
            rig.api.failConnect = true
            rig.connectFails()
            assertEquals(0, rig.api.connFrees.get(), "no quiche connection was made")
            rig.assertBuffersFreedOnce(FreeCountingFactory.Site.SockAddr, FreeCountingFactory.Site.ConnectArgs)
        }

    @Test
    fun aConnectFailingWhileItBuildsItsPartsFreesQuichesConnectionAndTheSockaddrsOnce() =
        runBlocking {
            val rig = Rig()
            rig.connectFails(wrapIo = { throw InjectedFailure() })
            assertEquals(1, rig.api.connFrees.get(), "quiche's connection is freed once")
            rig.assertBuffersFreedOnce(FreeCountingFactory.Site.SockAddr)
        }

    @Test
    fun aConnectFailingAfterItsPartsAreBuiltReleasesQuicheTheSockaddrsTheScopeAndTheSocketOnce() =
        runBlocking {
            val rig = Rig()
            rig.api.failQlog = true
            val closes = AtomicInteger()
            rig.connectFails(
                wrapIo = { io ->
                    object : DatagramBatchIo by io {
                        override fun close(): SocketClosed {
                            closes.incrementAndGet()
                            return io.close()
                        }
                    }
                },
            )
            assertEquals(1, rig.api.connFrees.get(), "quiche's connection is freed once")
            assertEquals(1, closes.get(), "the socket is closed once")
            rig.assertBuffersFreedOnce(FreeCountingFactory.Site.SockAddr)
            rig.monitor.assertEveryObserverEnded()
        }

    private inner class Rig {
        val api = FailingApi(platformQuicheApi())
        val monitor = WatchedMonitor()
        val factory =
            FreeCountingFactory(
                BufferFactory.network(),
                setOf(FreeCountingFactory.Site.EdgeMemory, FreeCountingFactory.Site.SockAddr, FreeCountingFactory.Site.ConnectArgs),
            )

        /** Connects to a peer that never answers, through [wrapIo], and requires the injected failure. */
        suspend fun connectFails(wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it }) {
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
                val capture =
                    QuicTraceCapture(
                        captureFor = {
                            QuicConnectionCapture(
                                sink = TraceSink { },
                                qlog = QlogTarget.File(File(qlogDir, "client.sqlog").path),
                            )
                        },
                        recordNetworkObservations = true,
                    )
                val failed =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            coreConnect(
                                host = "127.0.0.1",
                                port = silent.localPort,
                                serverName = "localhost",
                                options = coreTestOptions.copy(trace = capture, networkMonitor = NetworkMonitorSource.Supplied(monitor)),
                                factory = factory,
                                api = api,
                                timeout = 10.seconds,
                                readPolicy = ReadPolicy.Bounded(10.seconds),
                                writePolicy = WritePolicy.Bounded(10.seconds),
                                wrapIo = wrapIo,
                                loops = loops,
                                flight = TEST_FLIGHT,
                            )
                        }
                    }.exceptionOrNull()
                if (generateSequence(
                        failed,
                    ) { it.cause }.none { it is InjectedFailure }
                ) {
                    fail("the connect did not fail as injected: $failed")
                }
            }
        }

        fun assertBuffersFreedOnce(vararg expected: FreeCountingFactory.Site) {
            val buffers = factory.edgeBuffers()
            for (site in listOf(*expected, FreeCountingFactory.Site.EdgeMemory)) {
                assertTrue(buffers.any { it.site == site }, "the connect allocated a $site buffer through the counting factory")
            }
            val notOnce = buffers.filter { it.frees.get() != 1 }.map { it.toString() }
            assertEquals(emptyList(), notOnce, "buffers of the failed connect not freed exactly once")
        }
    }

    /** An api that counts quiche connection frees and fails `connect` or the qlog naming when told to. */
    private class FailingApi(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        val connFrees = AtomicInteger()

        @Volatile var failConnect = false

        @Volatile var failQlog = false

        override fun connect(
            serverNameAddr: Long,
            serverNameLen: Int,
            scidAddr: Long,
            scidLen: Int,
            localAddr: Long,
            localAddrLen: Int,
            peerAddr: Long,
            peerAddrLen: Int,
            config: QuicheConfig,
        ): QuicheConn =
            if (failConnect) {
                throw InjectedFailure()
            } else {
                inner.connect(serverNameAddr, serverNameLen, scidAddr, scidLen, localAddr, localAddrLen, peerAddr, peerAddrLen, config)
            }

        override fun connFree(conn: QuicheConn) {
            connFrees.incrementAndGet()
            inner.connFree(conn)
        }

        override fun connSetQlogPath(
            conn: QuicheConn,
            path: String,
            title: String,
            desc: String,
        ): Boolean = if (failQlog) throw InjectedFailure() else inner.connSetQlogPath(conn, path, title, desc)
    }

    /** A monitor that counts the collections of its state that began and those that ended. */
    private class WatchedMonitor : NetworkMonitor {
        private val began = AtomicInteger()
        private val ended = AtomicInteger()
        private val real = MutableStateFlow<NetworkState>(NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved))

        override val state: StateFlow<NetworkState> =
            object : StateFlow<NetworkState> by real {
                override suspend fun collect(collector: FlowCollector<NetworkState>): Nothing {
                    began.incrementAndGet()
                    try {
                        real.collect(collector)
                    } finally {
                        ended.incrementAndGet()
                    }
                }
            }

        override fun close() = Unit

        /** An observer launched into a scope nobody cancelled begins and never ends. */
        suspend fun assertEveryObserverEnded() {
            within(SETTLE) { began.get() > 0 }
            within(SETTLE) { ended.get() == began.get() }
            assertEquals(began.get(), ended.get(), "observers of the failed connect's network that never ended")
        }
    }

    private class InjectedFailure : RuntimeException("injected")

    private companion object {
        val SETTLE: Duration = 2.seconds

        suspend fun within(
            bound: Duration,
            done: () -> Boolean,
        ) {
            val mark = TimeSource.Monotonic.markNow()
            while (!done() && mark.elapsedNow() < bound) delay(10.milliseconds)
        }
    }
}
