package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.nativeMemoryAccess
import java.io.File
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/** quiche `PathState::to_c()`: -1 Failed, 0 Unknown, 1 Validating, 2 ValidatingMTU, 3 Validated. */
private const val PATH_STATE_FAILED = -1L
private const val PATH_STATE_VALIDATING = 1L

/**
 * **Does the caller-clock reach quiche's PATH VALIDATION timers?** — the prerequisite measurement for
 * DitchOoM/socket#449 layer 3.
 *
 * `SemanticSim`'s KDoc claims quiche's shipped C FFI has no caller-supplied clock, so timer-dependent
 * timelines can never be virtualized. That claim is stale — the #260 caller-clock patch rewrites every
 * `Instant::now()` in `quiche/src` to `crate::now()` and adds `quiche_set_virtual_time_nanos` — but
 * `JvmCallerClockTests` and `JvmCallerClockSimTests` only ever proved it for the **idle** timer and the
 * generic `connTimeout` countdown. Migration lives on a different timer entirely:
 * `Path::on_loss_detection_timeout` counts `probing_lost` against `MAX_PROBING_TIMEOUTS` (3) and only
 * then calls `on_failed_validation()`. Nothing here has ever asserted the injected clock drives *that*.
 *
 * It matters because the RFC 9000 §8.2.4 path-validation budget is ~3s of wall time per probe. Under a
 * real clock a few hundred seeded migration scenarios is an overnight run; if the virtual clock drives
 * it, the same search is seconds — the difference between "a test" and "a search", and the whole reason
 * layer 3 is tractable.
 *
 * ## The measurement is a mutation pair, not a reading of the header
 *
 * Both tests run the **identical** scenario against a real quiche client and a real quiche server in
 * one process with no OS sockets: handshake, server issues a spare SCID so the client has a DCID to
 * migrate to, client probes a second local address, and every datagram quiche sends *from* that address
 * is dropped — an unanswered PATH_CHALLENGE, the exact field condition behind #447. They differ in one
 * variable:
 *
 *  - [virtualTimeDrivesAnUnansweredProbeToFailedValidation] injects virtual time and asserts the path
 *    reaches `Failed`.
 *  - [withoutVirtualTimeAnUnansweredProbeNeverLeavesValidating] is the control: same pumping, same
 *    `connOnTimeout` calls, clock left real. The path must stay `Validating`.
 *
 * The control is what makes the first test evidence. If path validation failed there for any reason
 * other than the injected clock — pumping, the drops themselves, a probe quiche rejected outright — the
 * control would fail too. It also asserts the wall clock never came close to the budget the virtual run
 * measured, so "real time secretly did it" is excluded rather than assumed.
 */
class PathValidationVirtualClockTests {
    @Test
    fun virtualTimeDrivesAnUnansweredProbeToFailedValidation() {
        val outcome =
            try {
                runUnansweredProbe(useVirtualClock = true)
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(PathValidationVirtualClockTests::class, e)
                return
            }

        assertEquals(
            PATH_STATE_FAILED,
            outcome.finalProbePathState,
            "an unanswered PATH_CHALLENGE must drive the probe path to Failed under virtual time — " +
                "reached ${outcome.finalProbePathState} after ${outcome.timerFirings} connOnTimeout " +
                "firings across ${outcome.virtualElapsed} of virtual time. If this is still Validating(1) " +
                "the caller-clock does NOT reach Path::on_loss_detection_timeout and #449 layer 3 must " +
                "stay on the real clock.",
        )

        // Non-vacuity: the failure has to have cost real virtual time and real timer firings. A quiche
        // that failed the path instantly (e.g. it refused the probe) would satisfy the assert above.
        assertTrue(
            outcome.timerFirings >= 3,
            "MAX_PROBING_TIMEOUTS is 3, so failing validation must take at least 3 timer firings, took " +
                "${outcome.timerFirings} — a faster failure means the path died of something other than " +
                "probe loss and this measures nothing",
        )
        assertTrue(
            outcome.virtualElapsed >= 1.seconds,
            "the §8.2.4 budget is ~3s; virtual elapsed was only ${outcome.virtualElapsed}, which is too " +
                "little to be the path-validation timeline",
        )
        assertTrue(
            outcome.droppedProbeDatagrams >= 3,
            "expected at least one PATH_CHALLENGE per probing timeout to have been dropped, dropped " +
                "${outcome.droppedProbeDatagrams}",
        )

        // The connection itself must survive — we are measuring path validation, not an idle close.
        assertTrue(
            !outcome.connectionClosed,
            "the connection must still be alive when the probe path fails; if it closed, the timeline " +
                "measured was the idle timer, not path validation",
        )

        println(
            "[caller-clock probe] path validation FAILED under virtual time after " +
                "${outcome.timerFirings} firings / ${outcome.virtualElapsed} virtual / " +
                "${outcome.wallElapsedMs}ms wall (${outcome.droppedProbeDatagrams} challenges dropped)",
        )
    }

    @Test
    fun withoutVirtualTimeAnUnansweredProbeNeverLeavesValidating() {
        val outcome =
            try {
                runUnansweredProbe(useVirtualClock = false)
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(PathValidationVirtualClockTests::class, e)
                return
            }

        assertEquals(
            PATH_STATE_VALIDATING,
            outcome.finalProbePathState,
            "CONTROL: with the clock left real, the identical scenario must leave the probe path still " +
                "Validating — it reached ${outcome.finalProbePathState} in only ${outcome.wallElapsedMs}ms " +
                "of wall time. If this fails, the virtual-time test proves nothing: something other than " +
                "the injected clock is failing the path.",
        )

        // The control has to be cheap in wall time, else "real time did it" is not actually excluded.
        assertTrue(
            outcome.wallElapsedMs < 1_000,
            "CONTROL: the control must not itself burn a path-validation budget of wall time; it took " +
                "${outcome.wallElapsedMs}ms",
        )

        println(
            "[caller-clock probe] CONTROL: real clock, path still Validating after " +
                "${outcome.timerFirings} firings / ${outcome.wallElapsedMs}ms wall " +
                "(${outcome.droppedProbeDatagrams} challenges dropped)",
        )
    }
}

private data class ProbeOutcomeMeasurement(
    val finalProbePathState: Long,
    val timerFirings: Int,
    val virtualElapsed: Duration,
    val wallElapsedMs: Long,
    val droppedProbeDatagrams: Int,
    val connectionClosed: Boolean,
)

private fun testCertPath(name: String): String {
    val url =
        PathValidationVirtualClockTests::class.java.classLoader.getResource("certs/$name")
            ?: error("Test cert not found: certs/$name")
    return File(url.toURI()).absolutePath
}

/**
 * Drive a real client/server quiche pair to an unanswered path probe and report what the probe path's
 * validation state became.
 *
 * With [useVirtualClock] the loop advances an injected virtual clock to each armed deadline before
 * firing `connOnTimeout`; without it the clock is explicitly cleared and left real, so the same
 * `connOnTimeout` calls are no-ops against deadlines that have not arrived. Everything else — the
 * handshake, the SCID issuance, the probe, the drops, the number of iterations — is identical.
 */
@Suppress("LongMethod")
private fun runUnansweredProbe(useVirtualClock: Boolean): ProbeOutcomeMeasurement {
    val api = loadQuicheApi()
    val bufferFactory = BufferFactory.network()

    // Fake-but-valid sockaddrs: quiche only seeds path state with these; no OS socket exists anywhere.
    val clientAddr = InetSocketAddress("127.0.0.1", 42001)
    val serverAddr = InetSocketAddress("127.0.0.1", 42002)
    val probeAddr = InetSocketAddress("127.0.0.1", 42003) // the second local endpoint we probe from

    // A generous idle timeout: we are advancing seconds of virtual time on purpose, and quiche's
    // on_timeout() returns early once the idle timer expires — an idle close would silently replace
    // the timeline we are trying to measure.
    val idleTimeoutMs = 120_000L

    val serverCfg = api.configNew(QUICHE_PROTOCOL_VERSION)
    val clientCfg = api.configNew(QUICHE_PROTOCOL_VERSION)

    fun configureAlpn(cfg: QuicheConfig) {
        val alpn = encodeAlpnList(listOf("probe-clock"), bufferFactory)
        api.configSetApplicationProtos(cfg, alpn.nativeMemoryAccess!!.nativeAddress.toLong(), alpn.remaining())
        alpn.freeNativeMemory()
    }
    configureAlpn(serverCfg)
    configureAlpn(clientCfg)
    api.configSetMaxIdleTimeout(serverCfg, idleTimeoutMs)
    api.configSetMaxIdleTimeout(clientCfg, idleTimeoutMs)

    writeNullTerminatedString(testCertPath("cert.crt"), bufferFactory).let { certBuf ->
        val rc = api.configLoadCertChainFromPemFile(serverCfg, certBuf.nativeMemoryAccess!!.nativeAddress.toLong())
        certBuf.freeNativeMemory()
        check(rc == 0) { "Failed to load cert chain: $rc" }
    }
    writeNullTerminatedString(testCertPath("cert.key"), bufferFactory).let { keyBuf ->
        val rc = api.configLoadPrivKeyFromPemFile(serverCfg, keyBuf.nativeMemoryAccess!!.nativeAddress.toLong())
        keyBuf.freeNativeMemory()
        check(rc == 0) { "Failed to load private key: $rc" }
    }
    api.configVerifyPeer(clientCfg, false)

    // --- clock setup. The pin is a thread-local inside libquiche, so a leftover pin from another test
    // on this thread would silently poison the control; clear first in BOTH modes. ---
    api.clearThreadVirtualTime()
    val t0 = 500_000_000_000L // 500s from libquiche's fixed anchor; the absolute value is irrelevant
    var virtualNanos = t0
    if (useVirtualClock) api.setThreadVirtualTimeNanos(virtualNanos)

    val serverName = "localhost"
    val serverNameBuf = bufferFactory.allocate(serverName.length + 1)
    serverNameBuf.writeString(serverName, Charset.UTF8)
    serverNameBuf.writeByte(0)
    serverNameBuf.resetForRead()
    val clientScid = generateScid(bufferFactory, Random(7))
    val connectLocal = clientAddr.toNativeSockAddr(bufferFactory)
    val connectPeer = serverAddr.toNativeSockAddr(bufferFactory)
    val clientConn =
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
    serverNameBuf.freeNativeMemory()
    clientScid.freeNativeMemory()
    connectLocal.free()
    connectPeer.free()

    val serverScid = generateScid(bufferFactory, Random(9))
    val serverPeerSock = clientAddr.toNativeSockAddr(bufferFactory)
    val serverLocalSock = serverAddr.toNativeSockAddr(bufferFactory)
    val serverConn =
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
    serverScid.freeNativeMemory()

    val clientPeerSock = serverAddr.toNativeSockAddr(bufferFactory)
    val clientLocalSock = clientAddr.toNativeSockAddr(bufferFactory)
    val probeLocalSock = probeAddr.toNativeSockAddr(bufferFactory)
    val probePeerSock = serverAddr.toNativeSockAddr(bufferFactory)

    val clientRecvInfo = api.recvInfoNew(clientPeerSock.address, clientPeerSock.length, clientLocalSock.address, clientLocalSock.length)
    val serverRecvInfo = api.recvInfoNew(serverPeerSock.address, serverPeerSock.length, serverLocalSock.address, serverLocalSock.length)
    val clientSendInfo = api.sendInfoNew()
    val serverSendInfo = api.sendInfoNew()
    val out = bufferFactory.allocate(QuicheDriver.MAX_DATAGRAM_SIZE)
    val outAddr = out.nativeMemoryAccess!!.nativeAddress.toLong()
    val seqScratch = bufferFactory.allocate(8)
    val seqScratchAddr = seqScratch.nativeMemoryAccess!!.nativeAddress.toLong()

    val probeKey = api.decodePathKey(probeLocalSock.address)
    var droppedProbeDatagrams = 0

    // Move every datagram both directions until quiescent, dropping anything quiche sends FROM the
    // probe endpoint — an unanswered PATH_CHALLENGE, exactly what a dead cellular path looks like.
    fun pump() {
        var progress = true
        while (progress) {
            progress = false
            while (true) {
                val n = api.connSend(clientConn, outAddr, QuicheDriver.MAX_DATAGRAM_SIZE, clientSendInfo)
                if (n <= 0) break
                progress = true
                if (api.decodePathKey(api.sendInfoFromAddr(clientSendInfo)) == probeKey) {
                    droppedProbeDatagrams++
                    continue
                }
                api.connRecv(serverConn, outAddr, n, serverRecvInfo)
            }
            while (true) {
                val n = api.connSend(serverConn, outAddr, QuicheDriver.MAX_DATAGRAM_SIZE, serverSendInfo)
                if (n <= 0) break
                progress = true
                api.connRecv(clientConn, outAddr, n, clientRecvInfo)
            }
        }
    }

    val wallStart = System.nanoTime()
    try {
        // --- handshake ---
        pump()
        check(api.connIsEstablished(clientConn)) { "client failed to establish" }
        check(api.connIsEstablished(serverConn)) { "server failed to establish" }

        // --- both sides issue a spare SCID ---
        // The server's gives the client a spare DCID to probe *to*. The client's is equally required:
        // `create_path_on_client` refuses with OutOfIdentifiers unless `ids.available_scids() != 0`,
        // because the new path needs an unlinked source CID of the client's own to be identified by.
        // Issuing only the server's is what a first attempt at this test does, and it fails -18 with
        // `availableDcids=1` — a genuinely confusing signature worth naming here.
        val rng = Random(11)
        fun issueSpareScid(
            conn: QuicheConn,
            role: String,
        ) {
            check(api.connScidsLeft(conn) > 0L) { "$role has no SCID capacity to issue a spare" }
            val spareScid = generateScid(bufferFactory, rng)
            val token = bufferFactory.allocate(QuicheDriver.STATELESS_RESET_TOKEN_LEN)
            repeat(QuicheDriver.STATELESS_RESET_TOKEN_LEN) { token.writeByte(rng.nextInt(256).toByte()) }
            token.resetForRead()
            val rc =
                api.connNewScid(
                    conn,
                    spareScid.nativeMemoryAccess!!.nativeAddress.toLong(),
                    QUIC_MAX_CONN_ID_LEN,
                    token.nativeMemoryAccess!!.nativeAddress.toLong(),
                    true,
                    seqScratchAddr,
                )
            spareScid.freeNativeMemory()
            token.freeNativeMemory()
            check(rc >= 0) { "$role connNewScid failed: $rc" }
        }
        issueSpareScid(serverConn, "server")
        issueSpareScid(clientConn, "client")
        pump()
        check(api.connAvailableDcids(clientConn) > 0L) {
            "client never received the NEW_CONNECTION_ID — nothing to probe a new path with"
        }

        // --- probe the second local endpoint ---
        val probe =
            api.connProbePath(
                clientConn,
                probeLocalSock.address,
                probeLocalSock.length,
                probePeerSock.address,
                probePeerSock.length,
            )
        check(probe is ProbeOutcome.Probed) {
            "quiche refused the probe: code=${(probe as ProbeOutcome.Rejected).code} " +
                "availableDcids=${api.connAvailableDcids(clientConn)} " +
                "paths=${api.connStats(clientConn)?.pathsCount}"
        }
        pump() // flushes the first PATH_CHALLENGE, which the pump drops

        fun probePathState(): Long {
            val paths = api.connStats(clientConn)?.pathsCount ?: 0L
            // The probe path is the non-active one; path 0 is the primary the connection lives on.
            for (idx in 0 until paths) {
                val st = api.connPathStats(clientConn, idx) ?: continue
                if (!st.active) return st.validationState
            }
            return Long.MIN_VALUE // no inactive path found
        }

        check(probePathState() == PATH_STATE_VALIDATING) {
            "expected the probe path to be Validating right after the probe, was ${probePathState()}"
        }

        // --- the actual measurement: fire the armed timer repeatedly and see whether the path dies ---
        var timerFirings = 0
        var state = probePathState()
        val maxFirings = 40
        while (state != PATH_STATE_FAILED && timerFirings < maxFirings && !api.connIsClosed(clientConn)) {
            if (useVirtualClock) {
                // Jump to the armed deadline (plus a nanosecond so it is strictly due), then fire.
                val remaining = api.connTimeout(clientConn) ?: break
                virtualNanos += remaining.inWholeNanoseconds + 1
                api.setThreadVirtualTimeNanos(virtualNanos)
            }
            api.connOnTimeout(clientConn)
            timerFirings++
            pump()
            state = probePathState()
        }

        return ProbeOutcomeMeasurement(
            finalProbePathState = state,
            timerFirings = timerFirings,
            virtualElapsed = (virtualNanos - t0).nanoseconds,
            wallElapsedMs = (System.nanoTime() - wallStart) / 1_000_000,
            droppedProbeDatagrams = droppedProbeDatagrams,
            connectionClosed = api.connIsClosed(clientConn),
        )
    } finally {
        api.clearThreadVirtualTime()
        out.freeNativeMemory()
        seqScratch.freeNativeMemory()
        api.sendInfoFree(clientSendInfo)
        api.sendInfoFree(serverSendInfo)
        api.recvInfoFree(clientRecvInfo)
        api.recvInfoFree(serverRecvInfo)
        api.connFree(clientConn)
        api.connFree(serverConn)
        clientPeerSock.free()
        clientLocalSock.free()
        serverPeerSock.free()
        serverLocalSock.free()
        probeLocalSock.free()
        probePeerSock.free()
        api.configFree(clientCfg)
        api.configFree(serverCfg)
    }
}
