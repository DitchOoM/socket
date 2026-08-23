@file:OptIn(ExperimentalDatagramApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/** Client-side local endpoints the sim mints, in open order: primary first, then each probe. */
private const val CLIENT_PORT_BASE = 42100
private const val SERVER_PORT = 42002

/**
 * #449 **layer 3**: a deterministic migration simulator — a real quiche client and a real quiche
 * server in one process, joined by a [MultiPathPipe], running entirely on `runTest` virtual time with
 * quiche's own internal clock driven from the same scheduler.
 *
 * ## Why this exists
 * Every defect in the CID/path family — #437, #441, #445, #447, #393, #395 — was found **on a phone**,
 * because a phone was the only place it could be found. `SemanticSim`, the existing Tier-B harness, is
 * built "minus the DatagramChannel/migration wiring": it passes
 * [MigrationCapability.BackendCannotMigrate], so the entire path lifecycle has had *zero* deterministic
 * coverage. This harness closes that: migration becomes a seeded, virtual-time scenario instead of a
 * walk around the block with a phone.
 *
 * ## The two things that make it possible
 *  1. **Per-path impairment** ([PathImpairment]) — "old path 80ms, new path 35ms" is the #445 overtake
 *     window as a config value, and a blackholed probe path is #447's unanswered PATH_CHALLENGE.
 *  2. **Virtual time reaching quiche** — [SimClock] reports [DriverTime.Virtual], so [QuicheDriver]
 *     installs [CallerClockQuicheApi] and libquiche's internal `Instant::now()` reads the test
 *     scheduler. `PathValidationVirtualClockTests` measured this against the *migration* timers
 *     specifically: an unanswered probe reaches `PathState::Failed` after 7.168s of virtual time in
 *     **0ms of wall time**, where a real clock never leaves `Validating`. Without that, one #447
 *     scenario would cost ~3s of wall clock and a search would be an overnight run.
 *
 * ## Why the server is pumped rather than run in `clientMode`
 * [SemanticSim] runs its server driver with `clientMode = true` so the driver's own reader loop pulls
 * from the pipe — fine for one path, wrong the moment there are two. quiche recognises a client's new
 * path only if the server hands it a `recv_info` whose `from` is the datagram's **real** source, and a
 * `clientMode` reader has exactly one `recv_info` fixed at construction. So the server here mirrors
 * `SharedQuicheServer` instead: [serverPump] takes each datagram's source from
 * [MultiPathPipe.receiveAtServer] and submits it as [PacketSource.FromServerSocket] with a per-source
 * `recv_info`, cached the same way the production server caches its own.
 *
 * ## What this does NOT replace
 * `RetiredCidInFlightPacketTestSuite` and `FailedProbeConnectionIdTestSuite` stay exactly as they are.
 * They double as the standing per-platform check that each target's `libquiche` was built with the
 * #445 patch, and a JVM-only sim cannot prove anything about the Apple or Linux native. Layer 3 *adds*
 * a discovery channel; it does not retire a per-platform guard.
 */
internal class MigrationSimScope(
    val clientDriver: QuicheDriver,
    val serverDriver: QuicheDriver,
    val pipe: MultiPathPipe,
    val api: QuicheApi,
    private val clientConn: QuicheConn,
    private val factory: PipeUdpChannelFactory,
) {
    /** Local endpoints the client has bound, in open order. Index 0 is the primary. */
    fun clientPaths(): List<InetSocketAddress> = factory.opened()

    /** Ask the client to migrate to a fresh local endpoint, as `QuicScope.migrate()` does. */
    suspend fun migrate(): CompletableDeferred<MigrationResult> {
        val deferred = CompletableDeferred<MigrationResult>()
        clientDriver.commands.send(QuicheCmd.Migrate(MigrationTarget.FreshLocalEndpoint, deferred))
        return deferred
    }

    /**
     * How many spare destination CIDs the client currently holds — the #447 observable, for diagnostics.
     *
     * ⚠️ Only valid while the driver is **quiescent**: quiche is single-threaded and the driver coroutine
     * owns it, so call this from the test body after `runCurrent()`, never while a wake is in flight.
     * Assertions should prefer the outcome of a later [migrate] — "can this connection still move" is the
     * property that matters and it is not timing-dependent, which is the same reason
     * [FailedProbeConnectionIdTestSuite] asserts on a migration rather than on a count.
     */
    fun clientAvailableDcids(): Long = api.connAvailableDcids(clientConn)

    /**
     * Suspend until the peer's NEW_CONNECTION_ID has landed and the client holds at least
     * [count] spare destination CIDs.
     *
     * Migration is not available the instant a connection establishes: quiche does not auto-issue CIDs,
     * so the peer's driver mints them on its first established wake and the frame still has to cross the
     * pipe. Calling `migrate()` before that answers [MigrationResult.Unmoved.Failed.NoSpareConnectionId]
     * — which is a real product behaviour (#448 is exactly this race) but not what most scenarios mean to
     * test, so they say so explicitly by waiting here.
     *
     * The `delay` is virtual, so the wait costs no wall clock; it drives the scheduler, which is what
     * lets the pipe's queued deliveries run.
     */
    suspend fun awaitSpareDcids(
        count: Long = 1,
        timeout: Duration = 30.seconds,
    ) {
        withTimeout(timeout) {
            while (clientAvailableDcids() < count) delay(10.milliseconds)
        }
    }
}

/**
 * [UdpChannelFactory] over a [MultiPathPipe]. Each `openPath` mints the next synthetic client local
 * endpoint and registers it with the pipe, so a path the driver opens is a path the pipe can impair.
 *
 * [impairmentFor] is consulted per opened path (index 1 is the first probe — index 0 is the primary,
 * which the harness opens directly), which is how a test says "the path I am about to migrate to is a
 * blackhole" or "…is 35ms while the old one is 80ms" without reaching inside the driver.
 */
internal class PipeUdpChannelFactory(
    private val pipe: MultiPathPipe,
    private val impairmentFor: (Int) -> PathImpairment,
    override val localEndpointSupport: LocalEndpointSupport = LocalEndpointSupport.Bindable,
) : UdpChannelFactory {
    private val paths = mutableListOf<InetSocketAddress>()

    fun opened(): List<InetSocketAddress> = paths.toList()

    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        val index = paths.size + 1 // +1: the primary was opened by the harness, not through here
        val local = InetSocketAddress("127.0.0.1", if (localPort != 0) localPort else CLIENT_PORT_BASE + index)
        val path = pipe.openPath(local, impairmentFor(index))
        paths += local
        return NewPath(
            channel = path.channel,
            localSockAddrAddress = path.sockAddr.address,
            localSockAddrLength = path.sockAddr.length,
            localEndpoint = QuicLocalEndpoint(local.hostString, local.port),
            // The pipe owns every path's sockaddr and frees them all in close(); releasing here would
            // free memory the pipe's own teardown still walks.
            release = {},
        )
    }
}

private fun migrationSimCertPath(name: String): String {
    val url =
        MultiPathPipe::class.java.classLoader.getResource("certs/$name")
            ?: error("Test cert not found: certs/$name")
    return File(url.toURI()).absolutePath
}

/** Sim options: TLS verification off (self-signed fixture cert), idle long enough not to race the scenario. */
internal fun migrationSimOptions(
    idleTimeout: Duration = 120.seconds,
    keepAliveInterval: Duration? = null,
): QuicOptions =
    QuicOptions(
        alpnProtocols = listOf("migsim"),
        verifyPeer = false,
        idleTimeout = idleTimeout,
        keepAliveInterval = keepAliveInterval,
        migration = MigrationPolicy.Manual,
    )

/**
 * Establish a real client/server quiche pair over a [MultiPathPipe] on [testScope]'s virtual time and
 * run [block] against it. Everything is torn down before returning.
 *
 * [primaryImpairment] applies to the connection's original path; [probeImpairment] is consulted for
 * each path the driver opens afterwards (argument is the 1-based probe index).
 */
internal suspend fun <R> withMigrationSim(
    testScope: TestScope,
    seed: Long,
    primaryImpairment: PathImpairment = PathImpairment(),
    probeImpairment: (Int) -> PathImpairment = { PathImpairment() },
    quicOptions: QuicOptions = migrationSimOptions(),
    establishTimeout: Duration = 60.seconds,
    block: suspend MigrationSimScope.() -> R,
): R {
    val api = loadQuicheApi()
    val bufferFactory = BufferFactory.network()
    val clock = SimClock(testScope.testScheduler)

    val clientRandom = Random(seed xor 0x434C49454E54L) // "CLIENT"
    val serverRandom = Random(seed xor 0x534552564552L) // "SERVER"

    val primaryLocal = InetSocketAddress("127.0.0.1", CLIENT_PORT_BASE)
    val serverAddr = InetSocketAddress("127.0.0.1", SERVER_PORT)

    return coroutineScope {
        val simJob = SupervisorJob(coroutineContext[Job])
        val simScope = CoroutineScope(coroutineContext + simJob)
        val pipe = MultiPathPipe(seed, simScope, api, bufferFactory)
        val primaryPath = pipe.openPath(primaryLocal, primaryImpairment)
        val factory = PipeUdpChannelFactory(pipe, probeImpairment)

        // --- configs (mirror the production server/client setups) ---
        val serverCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        val clientCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        listOf(serverCfg, clientCfg).forEach { cfg ->
            val alpn = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
            api.configSetApplicationProtos(cfg, alpn.nativeMemoryAccess!!.nativeAddress.toLong(), alpn.remaining())
            alpn.freeNativeMemory()
            applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, cfg))
        }
        writeNullTerminatedString(migrationSimCertPath("cert.crt"), bufferFactory).let { buf ->
            val rc = api.configLoadCertChainFromPemFile(serverCfg, buf.nativeMemoryAccess!!.nativeAddress.toLong())
            buf.freeNativeMemory()
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }
        writeNullTerminatedString(migrationSimCertPath("cert.key"), bufferFactory).let { buf ->
            val rc = api.configLoadPrivKeyFromPemFile(serverCfg, buf.nativeMemoryAccess!!.nativeAddress.toLong())
            buf.freeNativeMemory()
            check(rc == 0) { "Failed to load private key: $rc" }
        }

        // --- client connection ---
        val serverName = "localhost"
        val serverNameBuf = bufferFactory.allocate(serverName.length + 1)
        serverNameBuf.writeString(serverName, Charset.UTF8)
        serverNameBuf.writeByte(0)
        serverNameBuf.resetForRead()
        val clientScid = generateScid(bufferFactory, clientRandom)
        val clientPeerSock = serverAddr.toNativeSockAddr(bufferFactory)
        val clientConn =
            try {
                api.connect(
                    serverNameBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                    serverName.length,
                    clientScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_MAX_CONN_ID_LEN,
                    primaryPath.sockAddr.address,
                    primaryPath.sockAddr.length,
                    clientPeerSock.address,
                    clientPeerSock.length,
                    clientCfg,
                )
            } finally {
                serverNameBuf.freeNativeMemory()
                clientScid.freeNativeMemory()
            }
        val clientRecvInfo =
            api.recvInfoNew(clientPeerSock.address, clientPeerSock.length, primaryPath.sockAddr.address, primaryPath.sockAddr.length)
        val clientSendInfo = api.sendInfoNew()

        // --- server connection (eager accept: quiche_accept never reads the Initial) ---
        val serverScid = generateScid(bufferFactory, serverRandom)
        val serverLocalSock = serverAddr.toNativeSockAddr(bufferFactory)
        val serverConn =
            try {
                api.accept(
                    serverScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_MAX_CONN_ID_LEN,
                    0L,
                    0,
                    serverLocalSock.address,
                    serverLocalSock.length,
                    primaryPath.sockAddr.address,
                    primaryPath.sockAddr.length,
                    serverCfg,
                )
            } finally {
                serverScid.freeNativeMemory()
            }
        // The driver-level recv_info the server never actually uses (every packet arrives through
        // PacketSource.FromServerSocket with a per-source one), but cleanup() frees it, so it must exist.
        val serverRecvInfo =
            api.recvInfoNew(primaryPath.sockAddr.address, primaryPath.sockAddr.length, serverLocalSock.address, serverLocalSock.length)
        val serverSendInfo = api.sendInfoNew()

        val clientDriver =
            QuicheDriver(
                migration =
                    MigrationCapability.Supported(
                        peer = PinnedSockAddr(clientPeerSock.address, clientPeerSock.length),
                        primaryLocal = PinnedSockAddr(primaryPath.sockAddr.address, primaryPath.sockAddr.length),
                        channelFactory = factory,
                    ),
                rawApi = api,
                conn = clientConn,
                bufferFactory = bufferFactory,
                recvInfo = clientRecvInfo,
                sendInfo = clientSendInfo,
                udpChannel = primaryPath.channel,
                clientMode = true,
                isServer = false,
                keepAliveInterval = quicOptions.keepAliveInterval,
                clock = clock,
                driverContext = EmptyCoroutineContext,
                random = clientRandom,
                onCleanup = { clientPeerSock.free() },
            )
        val serverDriver =
            QuicheDriver(
                migration = MigrationCapability.ServerConnection,
                rawApi = api,
                conn = serverConn,
                bufferFactory = bufferFactory,
                recvInfo = serverRecvInfo,
                sendInfo = serverSendInfo,
                udpChannel = pipe.serverEgress,
                // NOT clientMode: the pump below owns ingress, because it is the only place that knows
                // each datagram's real source address. See the class KDoc.
                clientMode = false,
                isServer = true,
                keepAliveInterval = quicOptions.keepAliveInterval,
                clock = clock,
                driverContext = EmptyCoroutineContext,
                random = serverRandom,
                onCleanup = { serverLocalSock.free() },
            )

        // Per-source recv_info cache, exactly SharedQuicheServer's shape: quiche must be told the real
        // origin of every datagram, or a probe from a new client address looks like the old path and no
        // migration is ever recognised.
        val serverRecvInfos = HashMap<InetSocketAddress, QuicheRecvInfo>()
        val pump =
            simScope.launch {
                while (true) {
                    val datagram = pipe.receiveAtServer()
                    val info =
                        serverRecvInfos.getOrPut(datagram.from) {
                            val from = pipe.pathAt(datagram.from).sockAddr
                            api.recvInfoNew(from.address, from.length, serverLocalSock.address, serverLocalSock.length)
                        }
                    val buf = bufferFactory.allocate(datagram.bytes.size)
                    val bb = (buf.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
                    bb.clear()
                    bb.put(datagram.bytes)
                    serverDriver.commands.send(QuicheCmd.RecvPacket(buf, datagram.bytes.size, PacketSource.FromServerSocket(info) {}))
                }
            }

        serverDriver.start(simScope)
        clientDriver.start(simScope)

        try {
            withTimeout(establishTimeout) {
                clientDriver.state.first { it !is QuicConnectionState.Handshaking }
                serverDriver.state.first { it !is QuicConnectionState.Handshaking }
            }
            MigrationSimScope(clientDriver, serverDriver, pipe, api, clientConn, factory).block()
        } finally {
            withContext(NonCancellable) {
                pump.cancel()
                simJob.cancel()
                pipe.close()
                serverRecvInfos.values.forEach { api.recvInfoFree(it) }
                api.configFree(clientCfg)
                api.configFree(serverCfg)
            }
        }
    }
}
