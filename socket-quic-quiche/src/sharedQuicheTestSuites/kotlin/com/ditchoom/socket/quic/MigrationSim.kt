@file:OptIn(ExperimentalDatagramApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.sim.SimClock
import com.ditchoom.socket.quic.sim.SimClockChoice
import com.ditchoom.socket.quic.sim.resolve
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One-way latency every simulated path carries unless a scenario says otherwise — so the DEFAULT is a
 * realistic network and RTT≈0 is something a test has to ask for.
 *
 * ⚠️ **This default is a bug fix, not a detail.** It used to be zero, and that hid an incomplete fix
 * for #459: quiche re-arms `request_validation()` from an abandoned path's own loss timer ~75ms after
 * the driver's RFC 9000 §8.2.4 abandon, so whether a CID-lifecycle patch held came down to whether an
 * ACK beat that PTO — which it always does on loopback. The whole migration suite was green while the
 * shipped behaviour was broken on any real mobile path. 60ms one-way (120ms round trip) is both an
 * ordinary cellular RTT and comfortably past that ~75ms threshold, so the default now exercises the
 * class of defect rather than hiding it.
 *
 * The same trap in a different place cost #445 a day: "a loopback burst of 12 migrations survives BOTH
 * patched and unpatched (RTT≈0 = no overtake window), while a real ~40ms path reproduced it
 * immediately". Virtual time makes latency free — there is no reason for a scenario to be unrealistic
 * by accident.
 *
 * Note the deliberate asymmetry with the probe-path default ([PathImpairment] with no latency): a new
 * path faster than the one being left IS the #445 overtake window, so the default scenario exercises
 * that too. A test that needs a specific relationship states both, as
 * [MigrationSimTestSuite.aMigrationStrandsInFlightPacketsBearingTheRetiredCid] does.
 */
internal val DEFAULT_PATH_LATENCY = 60.milliseconds

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/** Client-side local endpoints the sim mints, in open order: primary first, then each probe. */
private const val CLIENT_PORT_BASE = 42100

/**
 * How many probe endpoints past [CLIENT_PORT_BASE] the sim pre-resolves. A scenario that exhausts the
 * pool fails loudly in [PipeUdpChannelFactory.openPath] rather than silently resolving on the hot path.
 */
private const val CLIENT_PORT_POOL = 64
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
    val client: SimClientQuicConnection,
    val server: DriverQuicConnection,
    val clientDriver: QuicheDriver,
    val serverDriver: QuicheDriver,
    val pipe: MultiPathPipe,
    val api: QuicheApi,
    private val clientConn: QuicheConn,
    internal val serverConn: QuicheConn,
    val clientAudit: CidAuditQuicheApi,
    val serverAudit: CidAuditQuicheApi,
    val serverIngress: List<ServerIngress>,
    private val factory: PipeUdpChannelFactory,
) {
    /** Local endpoints the client has bound, in open order. Index 0 is the primary. */
    fun clientPaths(): List<SocketAddress> = factory.opened()

    /**
     * Ask the client to migrate to a fresh local endpoint, as an application calling
     * `QuicScope.migrate()` does — the **manual** entry point, used by the [MigrationPolicy.Manual]
     * scenarios that drive every handoff themselves.
     *
     * Returns the deferred rather than the result so the caller can send the command and *then* keep
     * running: `aMigrationStrandsInFlightPacketsBearingTheRetiredCid` depends on the command being
     * queued synchronously, before the traffic it races is scheduled.
     *
     * Under [MigrationPolicy.Automatic] the attempts come from [wireAutoMigration] instead, and are
     * recorded on [client] — see [SimClientQuicConnection.attempts]. Both funnel into the same
     * [QuicheCmd.Migrate]; only the caller differs, which is precisely what the automatic scenarios
     * are testing.
     */
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

    /** Diagnostic: quiche's own client-side path table — index, validation state, active flag. */
    fun clientPathTable(): String {
        val n = api.connStats(clientConn)?.pathsCount ?: 0L
        return (0 until n).joinToString(" ") { idx ->
            val st = api.connPathStats(clientConn, idx)
            "[$idx state=${st?.validationState} active=${st?.active} rtt=${st?.rtt} var=${st?.rttvar} pto=${st?.totalPtoCount}]"
        }
    }

    /**
     * One read of the client's **active** path — the counters the shipped silence trigger actually
     * folds into its run.
     *
     * Not reachable through `clientDriver.stats()`: [QuicheCmd.Stats] reads path index 0, which is the
     * active path only until the first successful migration and never again. A scenario that migrates
     * before it kills anything must ask for the active path by search, exactly as
     * `QuicheDriver.sampleActivePathLiveness` does, or it measures the link the connection already left
     * — which is how the first investigation of
     * `aPathThatDiesBeforeItsRoundTripIsSampledStillReHomesInTime` came to read zero expiries off a dead
     * path and go looking in the wrong place.
     *
     * ⚠️ Only valid while the driver is **quiescent**, for the reason [clientAvailableDcids] gives.
     */
    fun clientActivePath(): ActivePathReading {
        val n = api.connStats(clientConn)?.pathsCount ?: return ActivePathReading.NoActivePath
        for (idx in 0 until n) {
            val st = api.connPathStats(clientConn, idx) ?: continue
            if (st.active) return ActivePathReading.Read(idx, st.totalPtoCount, st.rtt, st.rttvar)
        }
        return ActivePathReading.NoActivePath
    }

    /** Diagnostic: how many more source CIDs the server is still allowed to issue. */
    fun serverScidsLeft(): Long = api.connScidsLeft(serverConn)

    /** Diagnostic: how many source CIDs the server currently has outstanding. */
    fun serverActiveScids(): Int = api.connActiveScids(serverConn)

    /**
     * Diagnostic: the server's LIVE source connection IDs, hex. The reconciliation oracle #446 bound —
     * a plain read, never a drain — so "is this DCID still one the server recognises?" is answerable
     * as fact rather than inferred from a lagging retirement tally.
     */
    fun serverSourceIdsHex(): List<String> {
        val n = api.connActiveScids(serverConn)
        if (n <= 0) return emptyList()
        val slot = 1 + QUIC_MAX_CONN_ID_LEN
        val buf =
            com.ditchoom.buffer.BufferFactory
                .network()
                .allocate(n * slot)
        return try {
            val yielded = api.connReadSourceIds(serverConn, buf.nativeMemoryAccess!!.nativeAddress.toLong(), n)
            (0 until minOf(yielded, n)).mapNotNull { i ->
                buf.position(i * slot)
                val len = buf.readByte().toInt() and 0xff
                if (len <= 0 || len > QUIC_MAX_CONN_ID_LEN) {
                    null
                } else {
                    buf.readByteArray(len).joinToString("") { b -> hexByte(b) }
                }
            }
        } finally {
            buf.freeNativeMemory()
        }
    }

    /** Diagnostic: per-path datagram counts, so "was anything actually sent" is answerable. */
    fun pipeTraffic(): String =
        pipe.paths().joinToString(" ") {
            "[${it.local.port} ->srv=${it.stats.sentToServer} ->cli=${it.stats.sentToClient} bh=${it.stats.blackholed}]"
        }

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
 * The sim's **client** connection: a [DriverQuicConnection] (which is the server-side wrapper, and
 * answers [MigrationResult.Unmoved.Impossible.ServerConnection] to every `migrate`) with the one
 * member a client owns re-implemented — byte for byte as `JvmQuicConnection.migrate` does it.
 *
 * ## Why this type has to exist
 * Before it, the sim could only reach migration through [MigrationSimScope.migrate], which posts
 * [QuicheCmd.Migrate] to the driver directly. That is the right seam for the scenarios that script
 * every handoff themselves, and it is the wrong one for #453: the defect there was not in the driver
 * at all but in **who decides to call migrate, and when** ([wireAutoMigration]). A harness that can
 * only call the driver cannot test a reactor that calls the driver — so the sim now hands the real
 * reactor a real [QuicConnection] and lets it drive, exactly as the three platform `connect()` paths do.
 *
 * [attempts] is the record of what the reactor asked for and what it got. It is the honest observable
 * for a retry policy: counting opened probe paths would miss every attempt that answered
 * [MigrationResult.Unmoved.Failed.NoSpareConnectionId], because that one is decided *before*
 * `openPath` (`QuicheDriver.handleMigrate`) — and those are exactly the attempts a bounded backoff is
 * supposed to spend cheaply.
 */
internal class SimClientQuicConnection(
    private val driver: QuicheDriver,
    private val delegate: DriverQuicConnection,
) : QuicConnection by delegate {
    private val _attempts = mutableListOf<MigrationResult>()

    /** Every [migrate] outcome so far, in order — whoever asked, reactor or test body. */
    val attempts: List<MigrationResult> get() = _attempts.toList()

    override suspend fun migrate(target: MigrationTarget): MigrationResult =
        try {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(target, deferred))
            // Suspends until the path has validated and the active path has switched, or the attempt
            // has failed — the property the automatic reactor relies on instead of a quiet period.
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            MigrationResult.Unmoved.Impossible.ConnectionClosed
        }.also { _attempts += it }
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
    /**
     * Every local endpoint this factory can hand out, resolved **before** the sim starts and looked up
     * here without suspending.
     *
     * ⚠️ Resolution must not happen on this path. `UdpSocket.resolve` is a suspend function, and
     * [openPath] is called by the driver mid-migration: suspending here leaves the test scheduler with
     * no runnable task, so `runTest` advances virtual time to the next deadline and the probe retry
     * cadence these suites measure collapses to a single attempt. Pre-resolving keeps [openPath]
     * synchronous in effect, which is what the pre-#556 `InetSocketAddress` constructor was.
     */
    private val localAddresses: Map<Int, SocketAddress>,
    override val localEndpointSupport: LocalEndpointSupport = LocalEndpointSupport.Bindable,
) : UdpChannelFactory {
    private val paths = mutableListOf<SocketAddress>()

    fun opened(): List<SocketAddress> = paths.toList()

    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        val index = paths.size + 1 // +1: the primary was opened by the harness, not through here
        val port = if (localPort != 0) localPort else CLIENT_PORT_BASE + index
        val local =
            localAddresses[port]
                ?: error("no pre-resolved local endpoint for port $port; widen CLIENT_PORT_POOL in withMigrationSim")
        val path = pipe.openPath(local, impairmentFor(index))
        paths += local
        return NewPath(
            channel = path.channel,
            localSockAddrAddress = path.sockAddr.address,
            localSockAddrLength = path.sockAddr.length,
            localEndpoint = QuicLocalEndpoint(local.host, local.port),
            // The pipe owns every path's sockaddr and frees them all in close(); releasing here would
            // free memory the pipe's own teardown still walks.
            release = {},
        )
    }
}

/**
 * One 1-RTT datagram as it reached the server: which client local port sent it, and the destination
 * connection ID it carries — which is one of the *server's* source CIDs, and therefore the thing #445
 * is about. A long-header packet has no fixed-length DCID here, so [dcid] is null for those.
 */
internal class ServerIngress(
    val fromPort: Int,
    val dcid: String?,
    /**
     * How many of this server's own source CIDs the peer had already retired when this datagram
     * arrived. Read from the audit's `connRetiredScids` tally, which the driver polls on its
     * established wakes — so it **lags** the retirement quiche processed inside `connRecv`, never
     * leads it. That direction is what makes it safe to assert on: `>= 1` means the retirement has
     * definitely happened, so a datagram still bearing the old CID is definitely a late one.
     */
    val retiredScidsSeenOnArrival: Int,
)

/**
 * The destination CID of a 1-RTT (short-header) packet, or null for a long-header one. A short header
 * carries no CID length, so the reader must already know it — every source CID this server issues is
 * [QUIC_MAX_CONN_ID_LEN] bytes (`generateScid`). Same decode as `HoldbackDatagramChannel`.
 */
internal fun shortHeaderDcidHex(datagram: PipeDatagram): String? {
    if (datagram.length < 1 + QUIC_MAX_CONN_ID_LEN) return null
    val buffer = datagram.readable()
    if (buffer.readByte().toInt() and 0x80 != 0) return null // long header — DCID is length-prefixed instead
    val hex = StringBuilder(QUIC_MAX_CONN_ID_LEN * 2)
    repeat(QUIC_MAX_CONN_ID_LEN) {
        val b = buffer.readByte().toInt() and 0xFF
        hex.append(HEX_DIGITS[b shr 4]).append(HEX_DIGITS[b and 0x0F])
    }
    return hex.toString()
}

/** `String.format` is JVM-only; these suites compile for Apple and Linux too. */
private const val HEX_DIGITS = "0123456789abcdef"

/** One byte as two lowercase hex digits, without `String.format`. */
private fun hexByte(b: Byte): String {
    val v = b.toInt() and 0xFF
    return "${HEX_DIGITS[v shr 4]}${HEX_DIGITS[v and 0x0F]}"
}

/**
 * Records the connection-ID calls each side makes, so a scenario can assert on the *mechanism*
 * ("the abandon retired the id it held") rather than only on the aggregate `available_dcids` count,
 * which several independent effects move.
 */
internal class CidAuditQuicheApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    val retireCalls = mutableListOf<Pair<Long, Int>>() // dcidSeq -> return code
    var newScidCalls = 0
        private set

    /** Every `quiche_conn_recv` return code, in order. #445's signature is -6 (QUICHE_ERR_INVALID_STATE). */
    val recvCodes = mutableListOf<Int>()

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int = delegate.connRecv(conn, buf, bufLen, recvInfo).also { recvCodes += it }

    /** Non-zero returns from `quiche_conn_retired_scid_iter`'s count — i.e. the peer retired one of ours. */
    var retiredScidsSeen = 0
        private set

    /** How many times the driver polled at all — separates "never asked" from "asked, nothing there". */
    var retiredScidPolls = 0
        private set

    override fun connRetiredScids(conn: QuicheConn): Int =
        delegate.connRetiredScids(conn).also {
            retiredScidPolls++
            if (it > 0) retiredScidsSeen += it
        }

    override fun connRetireDcid(
        conn: QuicheConn,
        dcidSeq: Long,
    ): Int = delegate.connRetireDcid(conn, dcidSeq).also { retireCalls += dcidSeq to it }

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int =
        delegate.connNewScid(conn, scidAddr, scidLen, resetTokenAddr, retireIfNeeded, seqOut).also {
            newScidCalls++
        }
}

/**
 * A path written into native memory as a NUL-terminated C string, for quiche's `*_from_pem_file` calls.
 *
 * A test-local twin of `commonJvmWithQuicServer`'s `writeNullTerminatedString`: that one is `internal`
 * to `commonJvmMain` and its Apple/Linux counterparts are `private`, so none of the three is reachable
 * from a source set that compiles for all of them. Named distinctly so the JVM compilation, which does
 * see the `commonJvmMain` one, has no ambiguity.
 */
internal fun simNullTerminated(
    path: String,
    factory: BufferFactory,
): PlatformBuffer {
    val buf = factory.allocate(path.length + 1)
    buf.writeString(path, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}

/**
 * Sim options: TLS verification off (self-signed fixture cert), idle long enough not to race the
 * scenario.
 *
 * [migration] defaults to [MigrationPolicy.Manual] — the scenarios that script every handoff through
 * [MigrationSimScope.migrate]. Pass [MigrationPolicy.Automatic] together with a
 * [NetworkMonitorSource.Supplied] scriptable monitor to put the real [wireAutoMigration] reactor in
 * charge instead.
 *
 * [networkMonitor] defaults to a supplied [NetworkMonitor.AlwaysAvailable] rather than
 * [NetworkMonitorSource.ProcessDefault]: the sim has no OS sockets anywhere by design, and the
 * process default would resolve a real platform monitor with a real background thread the moment the
 * harness touched it. `AlwaysAvailable` never changes identity, so it is also the honest "nothing is
 * observing this connection" for the manual scenarios.
 */
internal fun migrationSimOptions(
    idleTimeout: Duration = 120.seconds,
    keepAliveInterval: Duration? = null,
    migration: MigrationPolicy = MigrationPolicy.Manual,
    networkMonitor: NetworkMonitorSource = NetworkMonitorSource.Supplied(NetworkMonitor.AlwaysAvailable),
): QuicOptions =
    QuicOptions(
        alpnProtocols = listOf("migsim"),
        verifyPeer = false,
        idleTimeout = idleTimeout,
        keepAliveInterval = keepAliveInterval,
        migration = migration,
        networkMonitor = networkMonitor,
    )

/**
 * Everything about a migration-sim run that is decided by the platform rather than the scenario: which
 * `libquiche` binding to drive, where this target's test certificates are, and the C `sockaddr` layout
 * its OS uses.
 *
 * It is a parameter rather than something the sim discovers, because every way of discovering it is
 * platform-specific: `loadQuicheApi()` exists only on JVM/Android, the certificate fixtures are found
 * by classpath on the JVM and by filesystem probe on Kotlin/Native (and can be legitimately absent on a
 * simulator — see #359), and the sockaddr layout differs by OS. A per-platform test subclass supplies
 * one, exactly as [RetiredCidInFlightPacketTestSuite] takes its `testTlsConfig`/`platformQuicheApi`.
 */
internal class MigrationSimEnv(
    val api: QuicheApi,
    val certChainPath: String,
    val privKeyPath: String,
    val codec: SocketAddressCodec,
)

/**
 * Establish a real client/server quiche pair over a [MultiPathPipe] on the calling `runTest` scheduler's
 * virtual time and run [block] against it. Everything is torn down before returning.
 *
 * [primaryImpairment] applies to the connection's original path; [probeImpairment] is consulted for
 * each path the driver opens afterwards (argument is the 1-based probe index).
 *
 * [clock] defaults to [SimClockChoice.Virtual] rather than the calling dispatcher's clock because this
 * harness exists for virtual time (see the class KDoc: 7.168s of §8.2.4 budget in 0ms of wall); it is
 * resolved against the calling dispatcher before anything native is allocated, so calling this from a
 * real dispatcher — or asking for [SimClockChoice.Wall] under `runTest` — fails at construction (#497).
 */
internal suspend fun <R> withMigrationSim(
    env: MigrationSimEnv,
    seed: Long,
    primaryImpairment: PathImpairment = PathImpairment(latency = DEFAULT_PATH_LATENCY),
    probeImpairment: (Int) -> PathImpairment = { PathImpairment() },
    quicOptions: QuicOptions = migrationSimOptions(),
    establishTimeout: Duration = 60.seconds,
    clock: SimClockChoice = SimClockChoice.Virtual,
    /**
     * When the client's active path counts as having stopped answering. Defaults to the shipped
     * [SILENT_PATH_THRESHOLD]; a scenario passes another to measure where the boundary is rather than
     * asserting the constants back to themselves. Client only — the server never migrates.
     */
    silenceThreshold: SilenceThreshold = SILENT_PATH_THRESHOLD,
    block: suspend MigrationSimScope.() -> R,
): R {
    // First, before anything native is allocated: an incoherent clock fails here, typed, with nothing
    // to tear down.
    val driverClock = clock.resolve()
    val api = env.api
    val codec = env.codec
    val bufferFactory = BufferFactory.network()

    val clientRandom = Random(seed xor 0x434C49454E54L) // "CLIENT"
    val serverRandom = Random(seed xor 0x534552564552L) // "SERVER"

    // Resolve every address the sim can use up front — see PipeUdpChannelFactory.localAddresses for
    // why resolution must never happen once the driver is running.
    val clientAddresses =
        (0..CLIENT_PORT_POOL).associate { i ->
            (CLIENT_PORT_BASE + i) to
                UdpSocket.resolve("127.0.0.1", CLIENT_PORT_BASE + i)
        }
    val primaryLocal = clientAddresses.getValue(CLIENT_PORT_BASE)
    val serverAddr = UdpSocket.resolve("127.0.0.1", SERVER_PORT)

    return coroutineScope {
        val simJob = SupervisorJob(coroutineContext[Job])
        val simScope = CoroutineScope(coroutineContext + simJob)
        val ledger = DatagramLedger(bufferFactory)
        val pipe = MultiPathPipe(seed, simScope, api, bufferFactory, codec, ledger)
        val primaryPath = pipe.openPath(primaryLocal, primaryImpairment)
        val factory = PipeUdpChannelFactory(pipe, probeImpairment, clientAddresses)

        // --- configs (mirror the production server/client setups) ---
        val serverCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        val clientCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        listOf(serverCfg, clientCfg).forEach { cfg ->
            val alpn = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
            api.configSetApplicationProtos(cfg, alpn.nativeMemoryAccess!!.nativeAddress.toLong(), alpn.remaining())
            alpn.freeNativeMemory()
            applyQuicOptions(quicOptions, SimQuicConfigCalls(api, cfg))
        }
        simNullTerminated(env.certChainPath, bufferFactory).let { buf ->
            val rc = api.configLoadCertChainFromPemFile(serverCfg, buf.nativeMemoryAccess!!.nativeAddress.toLong())
            buf.freeNativeMemory()
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }
        simNullTerminated(env.privKeyPath, bufferFactory).let { buf ->
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
        val clientPeerSock = codec.encodeToNative(serverAddr, bufferFactory)
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
        val serverLocalSock = codec.encodeToNative(serverAddr, bufferFactory)
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

        val clientAudit = CidAuditQuicheApi(api)
        val serverAudit = CidAuditQuicheApi(api)

        val clientDriver =
            QuicheDriver(
                migration =
                    MigrationCapability.Supported(
                        peer = PinnedSockAddr(clientPeerSock.address, clientPeerSock.length),
                        primaryLocal = PinnedSockAddr(primaryPath.sockAddr.address, primaryPath.sockAddr.length),
                        channelFactory = factory,
                    ),
                rawApi = clientAudit,
                conn = clientConn,
                bufferFactory = bufferFactory,
                recvInfo = clientRecvInfo,
                sendInfo = clientSendInfo,
                udpChannel = primaryPath.channel,
                clientMode = true,
                isServer = false,
                keepAliveInterval = quicOptions.keepAliveInterval,
                clock = driverClock,
                driverContext = EmptyCoroutineContext,
                random = clientRandom,
                silenceThreshold = silenceThreshold,
                onCleanup = { clientPeerSock.free() },
            )
        val serverDriver =
            QuicheDriver(
                migration = MigrationCapability.ServerConnection,
                rawApi = serverAudit,
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
                clock = driverClock,
                driverContext = EmptyCoroutineContext,
                random = serverRandom,
                onCleanup = { serverLocalSock.free() },
            )

        // Per-source recv_info cache, exactly SharedQuicheServer's shape: quiche must be told the real
        // origin of every datagram, or a probe from a new client address looks like the old path and no
        // migration is ever recognised.
        val serverRecvInfos = HashMap<SocketAddress, QuicheRecvInfo>()
        val serverIngress = mutableListOf<ServerIngress>()
        val pump =
            simScope.launch {
                while (true) {
                    val datagram = pipe.receiveAtServer()
                    // Once this datagram leaves the channel the pump is its ONLY owner, and every exit
                    // from here — a throw building the recv_info, a cancelled `send`, a closed command
                    // channel — has to free it. A `finally` on a handoff flag covers all of them at once;
                    // enumerating the escapes one at a time is what left exactly one buffer stranded per
                    // run, and a pump that dies inside a SupervisorJob dies silently, so the leak was the
                    // only evidence it had happened at all.
                    val owned = datagram.datagram
                    var handedOff = false
                    try {
                        val info =
                            serverRecvInfos.getOrPut(datagram.from) {
                                val from = pipe.pathAt(datagram.from).sockAddr
                                api.recvInfoNew(from.address, from.length, serverLocalSock.address, serverLocalSock.length)
                            }
                        serverIngress += ServerIngress(datagram.from.port, shortHeaderDcidHex(owned), serverAudit.retiredScidsSeen)
                        // The pipe's buffer goes straight to the driver — no second copy. RecvPacket frees
                        // it (execute and failCommand both do), so ownership TRANSFERS rather than being
                        // released here.
                        serverDriver.commands.send(
                            QuicheCmd.RecvPacket(owned.buffer, owned.length, PacketSource.FromServerSocket(info) {}),
                        )
                        ledger.transfer(owned)
                        handedOff = true
                    } finally {
                        if (!handedOff) ledger.release(owned)
                    }
                }
            }

        serverDriver.start(simScope)
        clientDriver.start(simScope)

        val client =
            SimClientQuicConnection(
                clientDriver,
                DriverQuicConnection(clientDriver, bufferFactory, SocketAddress.ofLiteral("127.0.0.1", SERVER_PORT), simScope),
            )
        val server = DriverQuicConnection(serverDriver, bufferFactory, SocketAddress.ofLiteral("127.0.0.1", CLIENT_PORT_BASE), simScope)
        val result =
            try {
                withTimeout(establishTimeout) {
                    clientDriver.state.first { it !is QuicConnectionState.Handshaking }
                    serverDriver.state.first { it !is QuicConnectionState.Handshaking }
                }
                // The production entry point, called exactly where the three `QuicheEngine.connect()`
                // actuals call it: after the handshake, on the resolved monitor and the client driver's own
                // `pathLiveness` (#574's data-plane trigger), once per connection. A MigrationPolicy other
                // than Automatic makes this a no-op inside the reactor itself, so the manual scenarios below
                // are unaffected and the branch under test is the shipped one.
                wireAutoMigration(
                    quicOptions,
                    client,
                    resolveNetworkMonitor(quicOptions.networkMonitor),
                    clientDriver.pathLiveness,
                )
                MigrationSimScope(
                    client,
                    server,
                    clientDriver,
                    serverDriver,
                    pipe,
                    api,
                    clientConn,
                    serverConn,
                    clientAudit,
                    serverAudit,
                    serverIngress,
                    factory,
                ).block()
            } finally {
                withContext(NonCancellable) {
                    pump.cancel()
                    // cancelAndJoin, not cancel: in-flight datagrams free themselves as their delivery
                    // coroutine is cancelled, and the leak check below would otherwise race that.
                    simJob.cancelAndJoin()
                    pipe.close()
                    serverRecvInfos.values.forEach { api.recvInfoFree(it) }
                    api.configFree(clientCfg)
                    api.configFree(serverCfg)
                }
            }
        // Only on the success path: an exception above propagates from the `try` and must not be masked
        // by a leak report. `pipe.close()` has drained every queue by now, so anything still outstanding
        // was captured and then neither delivered, dropped, drained nor handed to a driver — a leak in
        // the harness itself, which is exactly what a harness that hunts leaks must not have.
        check(ledger.outstanding() == 0) {
            "the sim pipe leaked ${ledger.outstanding()} native datagram buffer(s): ${ledger.summary()}"
        }
        result
    }
}

/**
 * What a read of quiche's active path found — never a sentinel count or a zero duration standing in for
 * "there wasn't one".
 *
 * [NoActivePath] is a real and transient state of quiche's own bookkeeping, not an error: it clears the
 * active flag in `on_failed_validation()` and only picks a replacement on the next `on_timeout`, so a
 * scenario sampling across a switch can land inside that window. A scenario that needs the counters says
 * so with `assertIs`, and gets a failure naming the window rather than a `-1` folded into arithmetic.
 */
sealed interface ActivePathReading {
    data object NoActivePath : ActivePathReading

    class Read(
        val index: Long,
        val expiries: Long,
        val rtt: Duration,
        val rttvar: Duration,
    ) : ActivePathReading
}
