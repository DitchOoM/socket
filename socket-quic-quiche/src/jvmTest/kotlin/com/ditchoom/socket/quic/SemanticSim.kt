package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * W4 Tier-B semantic simulator (RFC_DETERMINISTIC_SIMULATION.md §4): a REAL quiche client and a
 * REAL quiche server, in one process, wired through an [ImpairedPipe] instead of OS sockets.
 *
 * Construction mirrors the two production sites exactly — the client side is
 * [buildJvmQuicConnection] minus the [java.nio.channels.DatagramChannel]/migration wiring, the
 * server side is [JvmQuicServer.acceptNewConnection] minus the Selector demux (a single-connection
 * sim needs no DCID routing, and `quiche_accept` never reads the Initial, so the server conn can
 * be created eagerly). The server driver runs `clientMode = true` so its own [QuicheDriver] reader
 * loop pulls datagrams off the pipe (in the real server the central receive loop does this);
 * `isServer = true` keeps server stream-ID parity and egress semantics.
 *
 * Determinism seams (W1): both drivers get `driverContext = EmptyCoroutineContext` (loops inherit
 * the caller's dispatcher — the virtual-time scheduler under `runTest`) and per-role seeded
 * [Random]s derived from the scenario seed (separate instances because `kotlin.random.Random` is
 * not thread-safe and the two drivers draw concurrently on real dispatchers).
 *
 * ### Virtual-time status (primary W4 finding)
 *
 * **Partially virtual-time-drivable.** Everything on OUR side of the FFI — driver loops, the pipe's
 * impairment `delay()`s, `withTimeout` establishment bounds — runs on the test scheduler, and a
 * LOSSLESS handshake completes entirely under `runTest` virtual time (proved by
 * `SemanticSimTests.lossless_handshake_completes_under_virtual_time`): it is a pure event cascade
 * through channels, never waiting on a quiche timer. What canNOT be virtualized is any path that
 * depends on quiche's OWN timers — loss recovery (PTO retransmits) and idle timeout — because the
 * RFC §4 premise that "quiche is caller-clocked" is wrong for the C API we bind:
 * `quiche_conn_timeout_as_nanos` / `quiche_conn_on_timeout` take no `now` parameter; quiche stamps
 * every received packet and computes every deadline against Rust's internal monotonic
 * `Instant::now()`. Advancing the coroutine test scheduler fires [DriverClock.armTimeout] wakes,
 * but the resulting `connOnTimeout` is a no-op until *real* time reaches quiche's internal
 * deadline, and `connTimeout` immediately re-arms at (roughly) the same remaining wait — a
 * virtual-time busy loop instead of a recovery. **Unlock**: quiche's Rust API grew caller-supplied
 * clocks via `Instant`-parameterised variants only in its internal crates; the shipped C FFI has
 * none, so full virtualization requires either patching quiche (a `now`-taking FFI surface) or
 * staying on Tier A (StubQuicheApi) for timer-dependent timelines. Until then, impaired scenarios
 * run on real dispatchers with scaled-down timers (small idle timeouts, millisecond pipe latency).
 */
internal class SemanticSimScope(
    val client: DriverQuicConnection,
    val server: DriverQuicConnection,
    val clientDriver: QuicheDriver,
    val serverDriver: QuicheDriver,
    val pipe: ImpairedPipe,
)

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/** Default sim options: TLS verification off (self-signed test cert), short idle for bounded tests. */
internal fun semanticSimOptions(
    idleTimeout: Duration = 10.seconds,
    keepAliveInterval: Duration? = null,
): QuicOptions =
    QuicOptions(
        alpnProtocols = listOf("semsim"),
        verifyPeer = false,
        idleTimeout = idleTimeout,
        keepAliveInterval = keepAliveInterval,
    )

private fun simCertPath(name: String): String {
    val url =
        ImpairedPipe::class.java.classLoader.getResource("certs/$name")
            ?: error("Test cert not found: certs/$name")
    return File(url.toURI()).absolutePath
}

/**
 * Run [block] against an established real-quiche client/server pair joined by an [ImpairedPipe]
 * built from [impairment]. Everything (drivers, configs, native handles, pipe) is torn down before
 * returning. Throws [UnsatisfiedLinkError] out of `loadQuicheApi()` before any allocation when the
 * bundled native lib is missing — callers skip like every other quiche jvmTest.
 */
internal suspend fun <R> withSemanticSim(
    impairment: ImpairmentConfig,
    quicOptions: QuicOptions = semanticSimOptions(),
    establishTimeout: Duration = 10.seconds,
    // W3 trace tap: attached to the CLIENT driver (channel decorator + state mirror + stats poll),
    // mirroring how a consumer records its client-side connection in the field (RFC §5).
    clientRecorder: com.ditchoom.socket.quic.trace.QuicTraceRecorder? = null,
    block: suspend SemanticSimScope.() -> R,
): R {
    val api = loadQuicheApi()
    val bufferFactory = BufferFactory.network()
    // Role-separated seeded entropy (scid + stateless-reset tokens). Distinct instances so
    // concurrent driver-side draws can't interleave with (and perturb) the pipe's own Random(seed).
    val clientRandom = Random(impairment.seed xor 0x434C49454E54L) // "CLIENT"
    val serverRandom = Random(impairment.seed xor 0x534552564552L) // "SERVER"

    // Fake-but-valid sockaddrs: quiche only ever hands these back through recv/send_info; no OS
    // socket exists anywhere in the sim.
    val clientAddr = InetSocketAddress("127.0.0.1", 42001)
    val serverAddr = InetSocketAddress("127.0.0.1", 42002)

    return coroutineScope {
        val simJob = SupervisorJob(coroutineContext[Job])
        val simScope = CoroutineScope(coroutineContext + simJob)
        val pipe = ImpairedPipe(impairment, simScope)

        // --- server config (mirrors buildJvmQuicServer) ---
        val serverCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        writeNullTerminatedString(simCertPath("cert.crt"), bufferFactory).use { certBuf ->
            val rc = api.configLoadCertChainFromPemFile(serverCfg, certBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }
        writeNullTerminatedString(simCertPath("cert.key"), bufferFactory).use { keyBuf ->
            val rc = api.configLoadPrivKeyFromPemFile(serverCfg, keyBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            check(rc == 0) { "Failed to load private key: $rc" }
        }
        encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
            api.configSetApplicationProtos(serverCfg, alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong(), alpnBuf.remaining())
        }
        applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, serverCfg))

        // --- client config (mirrors buildJvmQuicConnection; verifyPeer=false → no CA loading) ---
        val clientCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
        encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
            api.configSetApplicationProtos(clientCfg, alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong(), alpnBuf.remaining())
        }
        applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, clientCfg))

        // --- client conn ---
        val serverName = "localhost"
        val serverNameBuf = bufferFactory.allocate(serverName.length + 1)
        serverNameBuf.writeString(serverName, Charset.UTF8)
        serverNameBuf.writeByte(0)
        serverNameBuf.resetForRead()
        val clientScid = generateScid(bufferFactory, clientRandom)
        val connectPeer = serverAddr.toNativeSockAddr(bufferFactory)
        val connectLocal = clientAddr.toNativeSockAddr(bufferFactory)
        val clientConn =
            try {
                api.connect(
                    serverNameBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                    serverName.length,
                    clientScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_MAX_CONN_ID_LEN,
                    connectLocal.address,
                    connectLocal.length,
                    connectPeer.address,
                    connectPeer.length,
                    clientCfg,
                )
            } finally {
                serverNameBuf.freeNativeMemory()
                clientScid.freeNativeMemory()
                connectLocal.free()
                connectPeer.free()
            }

        // Persistent sockaddrs for the client driver's recv_info — kept alive via onCleanup, the
        // exact GC-dangling-pointer discipline of buildJvmQuicConnection.
        val clientPeerSock = serverAddr.toNativeSockAddr(bufferFactory)
        val clientLocalSock = clientAddr.toNativeSockAddr(bufferFactory)
        val clientRecvInfo = api.recvInfoNew(clientPeerSock.address, clientPeerSock.length, clientLocalSock.address, clientLocalSock.length)
        val clientSendInfo = api.sendInfoNew()

        // --- server conn (eager accept: quiche_accept never reads the Initial) ---
        val serverScid = generateScid(bufferFactory, serverRandom)
        val serverPeerSock = clientAddr.toNativeSockAddr(bufferFactory)
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
                    serverPeerSock.address,
                    serverPeerSock.length,
                    serverCfg,
                )
            } finally {
                serverScid.freeNativeMemory()
            }
        val serverRecvInfo = api.recvInfoNew(serverPeerSock.address, serverPeerSock.length, serverLocalSock.address, serverLocalSock.length)
        val serverSendInfo = api.sendInfoNew()

        val clientDriver =
            QuicheDriver(
                api = api,
                conn = clientConn,
                bufferFactory = bufferFactory,
                recvInfo = clientRecvInfo,
                sendInfo = clientSendInfo,
                udpChannel = pipe.clientEndpoint,
                clientMode = true,
                isServer = false,
                keepAliveInterval = quicOptions.keepAliveInterval,
                clock = RealDriverClock,
                driverContext = EmptyCoroutineContext,
                random = clientRandom,
                recorder = clientRecorder,
                onCleanup = {
                    clientPeerSock.free()
                    clientLocalSock.free()
                },
            )
        val serverDriver =
            QuicheDriver(
                api = api,
                conn = serverConn,
                bufferFactory = bufferFactory,
                recvInfo = serverRecvInfo,
                sendInfo = serverSendInfo,
                udpChannel = pipe.serverEndpoint,
                // clientMode=true runs the driver's own udpReaderLoop on the pipe endpoint — the
                // sim's stand-in for the real server's central Selector receive loop.
                clientMode = true,
                isServer = true,
                keepAliveInterval = quicOptions.keepAliveInterval,
                clock = RealDriverClock,
                driverContext = EmptyCoroutineContext,
                random = serverRandom,
                onCleanup = {
                    serverPeerSock.free()
                    serverLocalSock.free()
                },
            )

        // Server first, so its reader is parked on the pipe before the client's Initial flushes.
        serverDriver.start(simScope)
        clientDriver.start(simScope)

        val client = DriverQuicConnection(clientDriver, bufferFactory, simScope)
        val server = DriverQuicConnection(serverDriver, bufferFactory, simScope)
        try {
            withTimeout(establishTimeout) {
                clientDriver.state.first { it !is QuicConnectionState.Handshaking }
                serverDriver.state.first { it !is QuicConnectionState.Handshaking }
            }
            SemanticSimScope(client, server, clientDriver, serverDriver, pipe).block()
        } finally {
            withContext(NonCancellable) {
                // close() sends QuicheCmd.Close then destroy()-joins the driver, whose cleanup()
                // frees conn/recvInfo/sendInfo and fires onCleanup (sockaddr release).
                runCatching { client.close() }
                runCatching { server.close() }
                simJob.cancel() // reader loops + any in-flight impairment delay coroutines
                pipe.close()
                api.configFree(clientCfg)
                api.configFree(serverCfg)
            }
        }
    }
}
