package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.PinnedRadioRefusal
import com.ditchoom.socket.quic.PinnedRadioRefusedException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptionNotHonouredException
import com.ditchoom.socket.quic.QuicOptionRefusal
import com.ditchoom.socket.quic.QuicOptionRefusedException
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.StreamOpenPolicy
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
        declaration: QlogDeclaration = QlogDeclaration.Undeclared,
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

    private inline fun <reified E : QuicOptionRefusedException> refusedConnect(options: QuicOptions): E =
        runBlocking {
            var failure: E? = null
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                failure = assertFailsWith<E> { connectWith(options) }
                assertEquals(0, routesAsked.get(), "a refused connect resolves no route and opens no socket")
            }
            failure ?: throw AssertionError("skipped")
        }

    private val traced get() = coreTestOptions.copy(trace = tracing())

    @Test
    fun aConnectPinnedToARadioIsRefusedBecauseTheCoreCannotPinASocket() {
        val e = refusedConnect<PinnedRadioRefusedException>(traced.copy(migration = MigrationPolicy.Pinned(NetworkKind.Cellular)))
        assertEquals(PinnedRadioRefusal.EngineCannotPin, e.refusal)
        assertTrue(factoryThreads.isEmpty(), "a refused connect never asks the application's trace factory")
    }

    @Test
    fun aBoundedStreamOpenIsRefusedBecauseTheCoreWaitsUntilTheCallerCancels() {
        val e = refusedConnect<QuicOptionNotHonouredException>(traced.copy(streamOpenPolicy = StreamOpenPolicy.Bounded(3.seconds)))
        assertEquals(QuicOptionRefusal.StreamOpenNotBounded(3.seconds), e.refused)
        assertTrue(factoryThreads.isEmpty(), "a refused connect never asks the application's trace factory")
    }

    @Test
    fun aStandbyLinkIsRefusedBecauseTheCoreHoldsNone() {
        val e = refusedConnect<QuicOptionNotHonouredException>(traced.copy(standbyLink = StandbyLink.KeepCellularReady))
        assertEquals(QuicOptionRefusal.StandbyLinkNotHeld, e.refused)
        assertTrue(factoryThreads.isEmpty(), "a refused connect never asks the application's trace factory")
    }

    @Test
    fun aDeclaredBudgetedQlogIsRefusedWithoutAskingTheFactory() {
        val e = refusedConnect<QuicOptionNotHonouredException>(coreTestOptions.copy(trace = tracing(budgeted, QlogDeclaration.Budgeted)))
        assertEquals(QuicOptionRefusal.QlogNotBudgeted, e.refused)
        assertTrue(factoryThreads.isEmpty(), "a declared target is refused without minting a capture")
    }

    @Test
    fun anUndeclaredBudgetedQlogIsRefusedByAClientBeforeAnySocketAndOffTheCallingThread() {
        val caller = Thread.currentThread()
        val e = refusedConnect<QuicOptionNotHonouredException>(coreTestOptions.copy(trace = tracing(budgeted)))
        assertEquals(QuicOptionRefusal.QlogNotBudgeted, e.refused)
        assertEquals(1, factoryThreads.size, "the factory is asked once, to name the qlog")
        assertNotEquals(caller, factoryThreads[0], "and not on the calling thread")
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
                    assertFailsWith<PinnedRadioRefusedException> {
                        serveWith(traced.copy(migration = MigrationPolicy.Pinned(NetworkKind.Wifi)))
                    }
                assertEquals(PinnedRadioRefusal.EngineCannotPin, e.refusal)
                assertTrue(factoryThreads.isEmpty())
            }
        }

    @Test
    fun aServeWithAStandbyLinkIsRefusedBecauseTheCoreHoldsNone() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                val e =
                    assertFailsWith<QuicOptionNotHonouredException> {
                        serveWith(
                            coreTestOptions.copy(standbyLink = StandbyLink.KeepCellularReady),
                        )
                    }
                assertEquals(QuicOptionRefusal.StandbyLinkNotHeld, e.refused)
            }
        }

    @Test
    fun aServeWhoseTraceDeclaresABudgetedQlogIsRefusedWithoutAskingTheFactory() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                val e =
                    assertFailsWith<QuicOptionNotHonouredException> {
                        serveWith(coreTestOptions.copy(trace = tracing(budgeted, QlogDeclaration.Budgeted)))
                    }
                assertEquals(QuicOptionRefusal.QlogNotBudgeted, e.refused)
                assertTrue(factoryThreads.isEmpty(), "a refused serve mints no capture")
            }
        }

    @Test
    fun anIdleServeMintsNoCapture() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                for (declaration in listOf(QlogDeclaration.Undeclared, QlogDeclaration.NotBudgeted)) {
                    val server = serveWith(coreTestOptions.copy(trace = tracing(declaration = declaration)))
                    server.close()
                }
                assertTrue(factoryThreads.isEmpty(), "a server asks the factory only as it accepts a connection")
            }
        }

    @Test
    fun anUndeclaredBudgetedQlogOnAServerIsNotWrittenAndIsCounted() =
        runBlocking {
            skipOnMissingNativeLib(CoreOptionRefusalTests::class) {
                var counted = 0L
                withCoreEchoServer(options = coreTestOptions.copy(trace = tracing(budgeted))) { server ->
                    val client = connectCore(server.port)
                    client.connection.close(QuicError.NoError)
                    counted = server.counts.qlogsRefusedAtAccept
                }
                assertEquals(1, counted, "the accepted connection ran without its qlog, and the server counted it")
                assertEquals(1, factoryThreads.size)
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
