package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.PinnedRadioRefusal
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRefusal
import com.ditchoom.socket.quic.QuicRefusedException
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A core connect or serve whose options carry a value the core cannot honour fails with the typed refusal that names the
 * option, before it resolves a route, opens a socket or binds one; and the application's trace factory is asked only on the
 * setup lane, never on the calling thread and never by a refused connect.
 */
class CoreOptionRefusalTests {
    /** Counts the routes asked for: a connect that opened anything has asked for one. */
    private val routesAsked = AtomicInteger()

    /** The threads the application's trace factory ran on. */
    private val factoryThreads = CopyOnWriteArrayList<Thread>()

    private val budgeted = QlogTarget.Budgeted(QlogDirectory("/qlog", QlogBudget.of(1L shl 20, 2, 1L shl 26, 4)) { }, "conn")

    private fun tracing(
        target: QlogTarget = QlogTarget.Off,
        declaration: QlogDeclaration = QlogDeclaration.NotBudgeted,
    ) = QuicTraceCapture(
        captureFor = {
            factoryThreads.add(Thread.currentThread())
            QuicConnectionCapture(TraceSink { }, qlog = target)
        },
        qlog = declaration,
    )

    private suspend fun connectWith(options: QuicOptions) =
        coreConnect(
            mismatch = MismatchResponse.Fail,
            host = "127.0.0.1",
            port = 9,
            serverName = "localhost",
            options = options,
            factory = BufferFactory.network(),
            api = platformQuicheApi(),
            timeout = 2.seconds,
            readPolicy = ReadPolicy.Bounded(2.seconds),
            writePolicy = WritePolicy.Bounded(2.seconds),
            routeSource = {
                routesAsked.incrementAndGet()
                it.routeSourceAddress()
            },
        )

    private suspend fun serveWith(options: QuicOptions) =
        coreServe(
            mismatch = MismatchResponse.Fail,
            bind = loopbackV4(),
            tls = platformTlsConfig(),
            options = options,
            factory = BufferFactory.network(),
            api = platformQuicheApi(),
            readPolicy = ReadPolicy.Bounded(2.seconds),
            writePolicy = WritePolicy.Bounded(2.seconds),
        )

    private fun refusedConnect(options: QuicOptions): QuicRefusal =
        runBlocking {
            var failure: QuicRefusal? = null
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                failure = assertFailsWith<QuicRefusedException> { connectWith(options) }.refusal
                assertEquals(0, routesAsked.get(), "a refused connect resolves no route and opens no socket")
            }
            failure ?: throw AssertionError("skipped")
        }

    private val traced get() = coreTestOptions.copy(trace = tracing())

    @Test
    fun aConnectPinnedToARadioIsRefusedBecauseTheCoreCannotPinASocket() {
        val refusal = refusedConnect(traced.copy(migration = MigrationPolicy.Pinned(NetworkKind.Cellular)))
        assertEquals(QuicRefusal.RadioNotPinnable(NetworkKind.Cellular, PinnedRadioRefusal.EngineCannotPin), refusal)
        assertTrue(factoryThreads.isEmpty(), "a refused connect never asks the application's trace factory")
    }

    @Test
    fun aStandbyLinkIsRefusedBecauseTheCoreHoldsNone() {
        val refusal = refusedConnect(traced.copy(standbyLink = StandbyLink.KeepCellularReady))
        assertEquals<QuicRefusal>(QuicRefusal.StandbyLinkNotHeld, refusal)
        assertTrue(factoryThreads.isEmpty(), "a refused connect never asks the application's trace factory")
    }

    @Test
    fun aDeclaredBudgetedQlogIsHonouredWithoutAskingTheFactory() {
        assertIs<OptionVerdict.Honoured>(verdictOn(coreTestOptions.copy(trace = tracing(budgeted, QlogDeclaration.Budgeted))))
        assertTrue(factoryThreads.isEmpty(), "a declared target is judged without minting a capture")
    }

    @Test
    fun theTraceFactoryOfAConnectThatSucceedsNeverRunsOnTheCallingThread() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                val caller = Thread.currentThread()
                withCoreEchoServer { server ->
                    val client = connectCore(server.port, options = coreTestOptions.copy(trace = tracing()))
                    client.connection.close(QuicError.NoError)
                }
                assertEquals(1, factoryThreads.size)
                assertNotEquals(caller, factoryThreads[0])
            }
        }

    @Test
    fun aServePinnedToARadioIsRefusedBeforeItBinds() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                val e =
                    assertFailsWith<QuicRefusedException> {
                        serveWith(traced.copy(migration = MigrationPolicy.Pinned(NetworkKind.Wifi)))
                    }
                assertEquals<QuicRefusal>(QuicRefusal.RadioNotPinnable(NetworkKind.Wifi, PinnedRadioRefusal.EngineCannotPin), e.refusal)
                assertTrue(factoryThreads.isEmpty())
            }
        }

    @Test
    fun aServeWithAStandbyLinkIsRefusedBecauseTheCoreHoldsNone() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                val e =
                    assertFailsWith<QuicRefusedException> {
                        serveWith(
                            coreTestOptions.copy(standbyLink = StandbyLink.KeepCellularReady),
                        )
                    }
                assertEquals<QuicRefusal>(QuicRefusal.StandbyLinkNotHeld, e.refusal)
            }
        }

    @Test
    fun anIdleServeMintsNoCapture() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                serveWith(coreTestOptions.copy(trace = tracing())).close()
                assertTrue(factoryThreads.isEmpty(), "a server asks the factory only as it accepts a connection")
            }
        }

    /**
     * Every [QuicOptions] property is accounted for below: decided by [verdictOn] (refused for some value), honoured by
     * the core's setup, or inert because it is deprecated and read by no engine. A property added to [QuicOptions] fails
     * here until it is placed.
     */
    @Test
    fun everyQuicOptionsPropertyIsPlacedAsDecidedHonouredOrInert() {
        val decided = setOf("migration", "streamOpenPolicy", "standbyLink", "trace")
        val honoured =
            setOf(
                "alpnProtocols",
                "flowControl",
                "congestionControl",
                "pacing",
                "idleTimeout",
                "keepAliveInterval",
                "persistentStreams",
                "closeLinger",
                "maxUdpPayloadSize",
                "initialCongestionWindowPackets",
                "networkMonitor",
                "activeConnectionIdLimit",
                "verifyPeer",
                "trustedCaCertificatesPem",
                "serverCertificateHashes",
                "certificateHashVerification",
                "enablePmtuDiscovery",
                "enableEarlyData",
                "enableGrease",
                "datagrams",
                "resumption",
                "serverCertVerifiers",
                "appleTrustSource",
                "handshakePacing",
                "handshakePolicy",
                "udpBatching",
                "sessionStore",
                "incomingStreams",
                "abortCodes",
            )
        val inert = setOf("datagramStreamConflictPolicy", "appleAllowOversizedServerCert")
        val declared =
            QuicOptions::class.java.declaredFields
                .map { it.name }
                .filter { it != "Companion" }
                .toSet()
        assertEquals(declared, decided + honoured + inert, "options not placed, or placed but gone")
    }
}
