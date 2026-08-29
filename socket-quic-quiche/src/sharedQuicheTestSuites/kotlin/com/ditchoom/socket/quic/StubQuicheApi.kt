package com.ditchoom.socket.quic

import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * Minimal [QuicheApi] stub for driver unit tests. All native calls are no-ops.
 * Controllable via [established], [closed], and [streamRecvResult].
 *
 * Lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest` because the driver suites that
 * use it do: `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, and this
 * directory is `srcDir`'d into both, so one copy of the double serves jvm/apple/linux *and* the device
 * lane. It is `internal`, so each compilation gets its own — there is no cross-source-set leak. See
 * DitchOoM/socket#390.
 */
internal class StubQuicheApi : QuicheApi {
    @Volatile var established = true

    @Volatile var closed = false

    /** If non-null, [connPeerError] returns it — simulates the peer's typed CONNECTION_CLOSE reason. */
    @Volatile var peerError: QuicError? = null

    /** If non-null, [connLocalError] returns it — simulates our local typed CONNECTION_CLOSE reason. */
    @Volatile var localError: QuicError? = null

    @Volatile var streamRecvResult: StreamRecvResult = StreamRecvResult.Done

    /**
     * If non-null, the next [connSend] returns this value (then resets to null).
     * Lets tests force a single [QuicheDriver.flushOutgoing] iteration without
     * a real congestion-control state machine.
     */
    @Volatile var connSendOnce: Int? = null

    /**
     * When true, the FIRST [connSend] observed *after [connClose] has been called* returns one
     * datagram (1300B), then stops. This deterministically forces a single
     * [QuicheDriver.flushOutgoing] -> [UdpChannel.send] during the *close* command's afterCommand —
     * and only then, since every pre-close send happens before [connClose]. Tests gate that send to
     * pin the driver between completing the Close deferred and running updateState(), with no
     * dependence on connSendOnce timing or scheduler luck. See
     * ReactiveDriverTests.close_completes_only_after_state_is_synced.
     */
    @Volatile var emitOneDatagramOnClose = false

    @Volatile private var closeInitiated = false

    @Volatile private var closeDatagramEmitted = false

    // --- Config (all no-ops) ---
    override fun configNew(version: Int) = QuicheConfig(1L)

    override fun configFree(config: QuicheConfig) {}

    override fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ) = 0

    override fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    ) {}

    override fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {}

    override fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    ) {}

    override fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configSetActiveConnectionIdLimit(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    ) {}

    override fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    ) {}

    override fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    ) {}

    override fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    override fun configEnableEarlyData(config: QuicheConfig) {}

    override fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    ) {}

    // --- Connection ---
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
    ) = QuicheConn(1L)

    override fun connFree(conn: QuicheConn) {}

    /**
     * Sim/trace hook invoked on every [connRecv] with the packet length — lets the W2 simulation
     * harness stamp inbound packets the driver fed to quiche into its [SimTrace] with a virtual
     * timestamp. Additive: null (the default) keeps the stub byte-identical for existing tests.
     */
    @Volatile var onConnRecv: ((Int) -> Unit)? = null

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        onConnRecv?.invoke(bufLen)
        return 0
    }

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int {
        if (emitOneDatagramOnClose && closeInitiated && !closeDatagramEmitted) {
            closeDatagramEmitted = true
            return 1300
        }
        val once = connSendOnce
        if (once != null) {
            connSendOnce = null
            return once
        }
        connSendQueue.removeFirstOrNull()?.let { return it }
        return 0
    }

    /**
     * Scripted [connSend] queue, drained one value per call in FIFO order — after
     * [emitOneDatagramOnClose] and [connSendOnce] have had their say, so neither existing seam's
     * behavior changes. **Empty by default**, so every existing test keeps falling through to `0`.
     */
    val connSendQueue: ArrayDeque<Int> = ArrayDeque()

    /**
     * If non-empty, each [connStreamRecv] pops the next result (modelling quiche's sequence, e.g. a
     * data-chunk-with-FIN followed by Done). Falls back to [streamRecvResult] once drained.
     */
    val streamRecvSequence: ArrayDeque<StreamRecvResult> = ArrayDeque()

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ) = streamRecvSequence.removeFirstOrNull() ?: streamRecvResult

    /** When set, [connStreamSend] returns this instead of [bufLen] — e.g. -1 (QUICHE_ERR_DONE) or a real error. */
    @Volatile var connStreamSendResult: Int? = null

    /** Peer application error code [connStreamSend] reports alongside a STREAM_STOPPED/RESET result. */
    @Volatile var connStreamSendErrorCode: QuicAppErrorCode? = null

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult {
        val result = connStreamSendResult ?: bufLen
        // Mirror real quiche: out_error_code is written ONLY on STREAM_STOPPED / STREAM_RESET, and is
        // always present there (0 if the peer used 0). So default the code to 0 on those results when a
        // test didn't set one, and leave it null otherwise (a normal send carries no error code).
        val code =
            if (result == QuicheDriver.QUICHE_ERR_STREAM_STOPPED || result == QuicheDriver.QUICHE_ERR_STREAM_RESET) {
                connStreamSendErrorCode ?: QuicAppErrorCode(0)
            } else {
                connStreamSendErrorCode
            }
        return StreamSendResult(result, code)
    }

    /** Records each [connStreamShutdown] call as (streamId, direction, errorCode) so tests can assert resets. */
    val streamShutdowns = mutableListOf<Triple<Long, Int, Long>>()

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int {
        streamShutdowns += Triple(streamId.id, direction, err)
        return 0
    }

    /** Length [connPeerCert] reports (0 = "no peer certificate"). The stub has no real native [buf] to
     *  copy into, so it never writes — driver-plumbing tests only assert on the returned length. */
    @Volatile var peerCertLen: Int = 0

    override fun connPeerCert(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ) = peerCertLen

    override fun connPeerError(conn: QuicheConn): QuicError? = peerError

    override fun connLocalError(conn: QuicheConn): QuicError? = localError

    // --- Unreliable datagrams (RFC 9221) ---

    /** Records the last [configEnableDgram] call so tests can assert it was wired. */
    @Volatile var dgramEnabled: Boolean = false

    override fun configEnableDgram(
        config: QuicheConfig,
        enabled: Boolean,
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) {
        dgramEnabled = enabled
    }

    /** When set, [connDgramSend] returns this instead of [bufLen] — e.g. -1 (QUICHE_ERR_DONE) or a real error. */
    @Volatile var connDgramSendResult: Int? = null

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ) = connDgramSendResult ?: bufLen

    /** Drained first by [connDgramRecv]; falls back to [dgramRecvResult] when empty. */
    val dgramRecvSequence: ArrayDeque<StreamRecvResult> = ArrayDeque()

    @Volatile var dgramRecvResult: StreamRecvResult = StreamRecvResult.Done

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ) = dgramRecvSequence.removeFirstOrNull() ?: dgramRecvResult

    @Volatile var hasReadableDgram: Boolean = false

    override fun hasReadableDgram(conn: QuicheConn) = hasReadableDgram

    @Volatile var dgramMaxWritableLen: MaxDatagramSize = MaxDatagramSize.Unavailable

    override fun connDgramMaxWritableLen(conn: QuicheConn) = dgramMaxWritableLen

    override fun connIsEstablished(conn: QuicheConn) = established

    override fun connIsClosed(conn: QuicheConn) = closed

    /** When true, [connIsTimedOut] reports a timeout — drives the IdleTimeout close-reason fallback. */
    @Volatile var timedOut = false

    override fun connIsTimedOut(conn: QuicheConn) = timedOut

    /** Controllable quiche timeout. Null (default) = "no quiche timer pending", so the keepalive deadline
     *  is the only thing that can wake the driver loop — see the keepalive driver tests. */
    @Volatile var connTimeout: Duration? = null

    override fun connTimeout(conn: QuicheConn): Duration? = connTimeout

    /** Counts timer fires the driver handed to quiche (i.e. NOT turned into a keepalive PING). */
    var onTimeoutCount = 0
        private set

    /** When set, a handed-to-quiche timer fire idle-closes the connection (mirrors quiche's idle timeout). */
    @Volatile var closeOnTimeout = false

    override fun connOnTimeout(conn: QuicheConn) {
        onTimeoutCount++
        if (closeOnTimeout) {
            closed = true
            // A close caused by the idle timer IS a timeout, so report it as one. Keeping these coupled
            // stops a test from configuring the incoherent pair quiche can never produce — "closed for
            // no stated reason" that nonetheless answers `connIsTimedOut() == true` — which would let a
            // close from some entirely different cause present itself as an idle timeout.
            timedOut = true
        }
    }

    /** Counts reactive-keepalive PINGs the driver scheduled, so tests can assert on them. */
    var ackElicitingCount = 0
        private set

    /**
     * Sim/trace hook invoked on every [connSendAckEliciting] — lets the W2 simulation harness stamp
     * keepalive PINGs into its [SimTrace] with a virtual timestamp. Additive: null (the default)
     * keeps the stub byte-identical for existing tests.
     */
    @Volatile var onAckEliciting: (() -> Unit)? = null

    override fun connSendAckEliciting(conn: QuicheConn): Int {
        ackElicitingCount++
        onAckEliciting?.invoke()
        return 0
    }

    /**
     * Every [QuicError] the driver handed to [connClose], in order — i.e. what would have gone onto the
     * wire. A test that closes with a reason quiche cannot carry (no transport code) asserts here that the
     * frame carried NO_ERROR rather than the code's `-1` reinterpreted as `u64`.
     */
    val closeErrors = mutableListOf<QuicError>()

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int {
        closeInitiated = true
        closeErrors += error
        return 0
    }

    // --- Server (no-ops) ---
    override fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

    override fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

    override fun configLoadVerifyLocationsFromFile(
        config: QuicheConfig,
        pathAddr: Long,
    ) = 0

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
    ) = 0

    override fun accept(
        scidAddr: Long,
        scidLen: Int,
        odcidAddr: Long,
        odcidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ) = QuicheConn(1L)

    override fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ) = 0

    // --- Stream iteration ---

    /**
     * Stream IDs quiche reports as readable. Unlike [writableStreams] this is **not** consumed by a
     * poll: each [connReadable] takes a snapshot, mirroring real quiche, where a stream stays in
     * `readable()` until the application drains it. The driver polls it once per `afterCommand` (to
     * signal/accept streams) and once more at teardown (to drain quiche's remaining bytes into the
     * slots — the #318 fix), and both sweeps must see the same stream. Empty ⇒ exhausted iterator,
     * the stub's original behaviour.
     */
    val readableStreams: ArrayDeque<Long> = ArrayDeque()

    private var readableSnapshot: ArrayDeque<Long> = ArrayDeque()

    override fun connReadable(conn: QuicheConn): QuicheStreamIter {
        if (readableStreams.isEmpty()) return QuicheStreamIter(0L)
        readableSnapshot = ArrayDeque(readableStreams)
        return QuicheStreamIter(READABLE_ITER)
    }

    /**
     * Stream IDs to report as writable on the next [connWritable] poll, drained by [streamIterNext].
     * The driver calls `connWritable` once per `afterCommand` and drains the iterator, so a test arms
     * this right before triggering one command to fire exactly one `writableSignal` wakeup. Non-empty
     * ⇒ [connWritable] returns a live iterator handle ([WRITABLE_ITER]).
     */
    val writableStreams: ArrayDeque<Long> = ArrayDeque()

    override fun connWritable(conn: QuicheConn) = if (writableStreams.isEmpty()) QuicheStreamIter(0L) else QuicheStreamIter(WRITABLE_ITER)

    // The handle tells the two iterators apart, so a test can arm both without their ids crossing over.
    override fun streamIterNext(iter: QuicheStreamIter): QuicStreamId? =
        when (iter.handle) {
            READABLE_ITER -> readableSnapshot.removeFirstOrNull()
            else -> writableStreams.removeFirstOrNull()
        }?.let { QuicStreamId(it) }

    override fun streamIterFree(iter: QuicheStreamIter) {}

    // --- Helpers ---
    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ) = QuicheRecvInfo(1L)

    override fun recvInfoFree(info: QuicheRecvInfo) {}

    override fun sendInfoNew() = QuicheSendInfo(1L)

    override fun sendInfoFree(info: QuicheSendInfo) {}

    override fun sendInfoToAddr(info: QuicheSendInfo) = 0L

    override fun sendInfoToAddrLen(info: QuicheSendInfo) = 0

    /**
     * Scripted [sendInfoFromAddr] queue, drained one value per call in FIFO order. **Empty by
     * default**, so every existing test keeps the `0L` answer it has always had.
     */
    val sendInfoFromAddrQueue: ArrayDeque<Long> = ArrayDeque()

    override fun sendInfoFromAddr(info: QuicheSendInfo) = sendInfoFromAddrQueue.removeFirstOrNull() ?: 0L

    override fun sendInfoFromAddrLen(info: QuicheSendInfo) = 0

    /**
     * Synthetic sockaddr decoding: native pointer → UDP port, for the pointers a test has actually
     * minted. **Empty by default**, so every existing test keeps the `family = 0` answer it has always
     * had and [decodePathKey] keeps returning `PathKey(0, 0, 0, 0)` — this stub decodes no real memory
     * and must not pretend to.
     *
     * A migration test registers the sockaddrs it made up ([registerSockAddr]) so the primary path and
     * each probed path get **distinct** [PathKey]s. Without that they all collide on the zero key, the
     * probed path silently overwrites the primary in the driver's `paths` map, and a teardown assertion
     * would be measuring the wrong entry.
     */
    private val sockAddrPorts = mutableMapOf<Long, Int>()

    /** Declare that the sockaddr at [addr] is `127.0.0.1:[port]`. See [sockAddrPorts]. */
    fun registerSockAddr(
        addr: Long,
        port: Int,
    ) {
        sockAddrPorts[addr] = port
    }

    override fun sockAddrFamily(addr: Long) = if (addr in sockAddrPorts) 4 else 0

    override fun sockAddrPort(addr: Long) = sockAddrPorts[addr] ?: 0

    override fun sockAddrV4(addr: Long) = if (addr in sockAddrPorts) LOOPBACK_V4 else 0L

    override fun sockAddrV6Hi(addr: Long) = 0L

    override fun sockAddrV6Lo(addr: Long) = 0L

    // --- Path migration ---

    /**
     * Scripted [ProbeOutcome] queue, drained one outcome per [connProbePath] call in FIFO order.
     * Falls back to a fresh [ProbeOutcome.Probed] once drained, whose sequence number comes from
     * [nextProbeDcidSeq] — so an unscripted test keeps the success it has always had, and now also
     * gets a *distinct*, inspectable connection ID per probe.
     */
    val connProbeOutcomes: ArrayDeque<ProbeOutcome> = ArrayDeque()

    /**
     * The sequence number the next unscripted [connProbePath] hands out; incremented after each.
     *
     * Starts at 1, because 0 is the initial destination CID the connection is already living on
     * (RFC 9000 §5.1.1) — quiche's `lowest_available_dcid_seq()` can never hand it out again, and a
     * stub that did would let a test pass while the driver retired the id it was still using.
     */
    @Volatile var nextProbeDcidSeq = 1L

    /**
     * Every destination CID quiche has **linked to a path** and not yet been asked to retire — the
     * stub's mirror of `ids.dcids[seq].path_id`, and the direct measurement of the #447 leak.
     *
     * [connProbePath] links one (`create_path_on_client` → `link_dcid_to_path_id`), [connRetireDcid]
     * unlinks it, and nothing else touches it — exactly as in quiche, where `on_failed_validation()`
     * leaves `active_dcid_seq` untouched. A driver that abandons a path without retiring its id
     * leaves that id here forever, one per failed migration, which is what
     * `NoSpareConnectionId`-for-the-rest-of-the-connection looks like from the inside.
     *
     * Sequence 0 is present from construction: the connection is using the initial CID from the
     * moment it exists.
     */
    val linkedDcidSeqs: MutableSet<Long> = mutableSetOf(0L)

    /**
     * Which destination CID each probed path holds, keyed by the local sockaddr pointer the path was
     * probed with — the stub's `path.active_dcid_seq`. [connMigrate] reads it so that migrating to a
     * path reports the id that path is *actually* using, which is what quiche 0.29 does
     * (`migrate()` on an existing path returns `path.active_dcid_seq`, the one `probe_path` linked).
     */
    private val pathDcidSeqs = mutableMapOf<Long, Long>()

    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): ProbeOutcome {
        val outcome = connProbeOutcomes.removeFirstOrNull() ?: ProbeOutcome.Probed(nextProbeDcidSeq++)
        // Mirror quiche: only a probe that actually created a path consumes (links) an id. Every
        // failure inside create_path_on_client returns before link_dcid_to_path_id.
        if (outcome is ProbeOutcome.Probed) {
            linkedDcidSeqs += outcome.dcidSeq
            pathDcidSeqs[localAddr] = outcome.dcidSeq
        }
        return outcome
    }

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int {
        newScidCalls++
        // Mirror real quiche: a successful issue consumes one unit of the peer-granted capacity.
        if (scidsLeft > 0L) scidsLeft--
        return 0
    }

    /**
     * Scripted [MigrateOutcome] queue, drained one outcome per [connMigrate] call in FIFO order.
     * **Empty by default**: an unscripted migration reports the sequence number [connProbePath]
     * linked to *that* path, because that is what quiche answers and a stub that invented a
     * different one would make the driver retire an id the connection is still using.
     *
     * Script it to model a [MigrateOutcome.Rejected], or the (quiche-0.29-impossible) case where the
     * reported id disagrees with the probed one.
     */
    val connMigrateOutcomes: ArrayDeque<MigrateOutcome> = ArrayDeque()

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): MigrateOutcome {
        val outcome =
            connMigrateOutcomes.removeFirstOrNull()
                ?: MigrateOutcome.Migrated(pathDcidSeqs[localAddr] ?: INITIAL_DCID_SEQ)
        // Mirror quiche: a successful migrate onto a path with no destination CID links one to it
        // (`link_dcid_to_path_id` + `path.active_dcid_seq = Some(..)`). Normally the id is the one
        // probe_path already linked, so this is idempotent; a script that reports a different one
        // models quiche linking that one instead, which is what makes the probe's id orphaned.
        if (outcome is MigrateOutcome.Migrated) {
            linkedDcidSeqs += outcome.dcidSeq
            pathDcidSeqs[localAddr] = outcome.dcidSeq
        }
        return outcome
    }

    /** Records every [connRetireDcid] call's [dcidSeq], so tests can assert what the driver retired. */
    val retiredDcids: MutableList<Long> = mutableListOf()

    @Volatile var connRetireDcidResult: Int = 0

    override fun connRetireDcid(
        conn: QuicheConn,
        dcidSeq: Long,
    ): Int {
        retiredDcids += dcidSeq
        // Mirror quiche's `retire_dcid`: the id is removed from the table and unlinked from whatever
        // path held it. A refusal (connRetireDcidResult < 0) changes nothing, as in quiche, where the
        // OutOfIdentifiers guard returns before touching `ids`.
        if (connRetireDcidResult >= 0) linkedDcidSeqs -= dcidSeq
        return connRetireDcidResult
    }

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ) = 0

    /**
     * Spare destination connection ids the peer has issued (`quiche_conn_available_dcids`).
     *
     * **0 by default, on purpose** — that is what real quiche reports until the peer sends
     * NEW_CONNECTION_ID, and it is why [QuicheDriver.handleMigrate] bails at the DCID guard before it
     * ever probes. A test that wants to reach the probe has to say the peer issued one, exactly as a
     * real peer would have to.
     */
    @Volatile var availableDcids = 0L

    override fun connAvailableDcids(conn: QuicheConn) = availableDcids

    /**
     * Keyed to [established] rather than hardcoded, because that is what the real accessor is keyed to:
     * `quiche_conn_peer_transport_params` returns false until the handshake has processed the peer's
     * parameters. A stub that always answered "negotiated" would let a driver test pass a peer check the
     * real connection would still be waiting on.
     *
     * The negotiated values mirror this module's own defaults so a driver test reads plausible numbers;
     * nothing in [QuicheDriver] dispatches on any field but [PeerTransportParams.Negotiated.disableActiveMigration].
     */
    @Volatile var peerDisablesActiveMigration = false

    override fun connPeerTransportParams(conn: QuicheConn): PeerTransportParams =
        if (!established) {
            PeerTransportParams.NotYetNegotiated
        } else {
            PeerTransportParams.Negotiated(
                maxIdleTimeoutMillis = 10_000,
                maxUdpPayloadSize = 1350,
                initialMaxData = 10 * 1024 * 1024,
                initialMaxStreamDataBidiLocal = 1024 * 1024,
                initialMaxStreamDataBidiRemote = 1024 * 1024,
                initialMaxStreamDataUni = 1024 * 1024,
                initialMaxStreamsBidi = 100,
                initialMaxStreamsUni = 100,
                ackDelayExponent = 3,
                maxAckDelayMillis = 25,
                disableActiveMigration = peerDisablesActiveMigration,
                activeConnIdLimit = 4,
                maxDatagramFrameSize = -1,
            )
        }

    /**
     * Source-CID capacity the peer still allows us (`quiche_conn_scids_left`). **0 by default** — the
     * historical answer, keeping `issueSpareCids` a no-op in every existing test. A replenishment test
     * sets it and the stub then behaves like real quiche: each successful [connNewScid] consumes one.
     */
    @Volatile var scidsLeft = 0L

    /** Every [connNewScid] call, successful or not — how a test measures SCID issuance. */
    @Volatile var newScidCalls = 0
        private set

    override fun connScidsLeft(conn: QuicheConn) = scidsLeft

    /**
     * How many source CIDs the peer has retired (`quiche_conn_retired_scids`). **0 by default**, so a
     * test that has not scripted a retirement never reaches [connDrainRetiredScids] — the same
     * "historical answer" shape as [scidsLeft].
     */
    @Volatile var retiredScidCount = 0

    /** Every [connDrainRetiredScids] call — how a test proves the driver drains only when it must. */
    @Volatile var drainRetiredScidCalls = 0
        private set

    /** The `maxIds` of the last [connDrainRetiredScids] call — proves the buffer was sized from the count. */
    @Volatile var lastDrainCapacity = -1
        private set

    override fun connRetiredScids(conn: QuicheConn) = retiredScidCount

    /**
     * Yields **nothing**, for the reason [connPathEventNext] registers a sockaddr instead of writing
     * one: this stub decodes no real memory, and filling the driver's buffer with fabricated connection
     * IDs would be a lie dressed as coverage. What it does record is the *policy* — that the driver
     * drains exactly when the count says to, and sizes the buffer from that count.
     *
     * The bytes are covered where they mean something: `RetiredScidDrainTests` runs this against real
     * quiche over a real migration, which is also the only place the four FFI backends are exercised.
     */
    override fun connDrainRetiredScids(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int {
        drainRetiredScidCalls++
        lastDrainCapacity = maxIds
        retiredScidCount = 0
        return 0
    }

    /**
     * How many source CIDs quiche considers active (`quiche_conn_active_scids`). **0 by default** —
     * the [QuicheApi] "unbound backend" answer, which is what every existing test relies on: with 0
     * the driver publishes no routing projection at all.
     */
    @Volatile var activeScidCount = 0

    /** Every [connActiveScids] call — how a test measures the per-wake cost of the projection. */
    @Volatile var activeScidCalls = 0
        private set

    /**
     * What [connReadSourceIds] reports it yielded. **0 by default**, matching the interface default a
     * backend with no binding gives — which is also the half-bound case the driver must refuse to
     * publish, since an empty set would unroute the whole connection.
     */
    @Volatile var sourceIdsYielded = 0

    /** Every [connReadSourceIds] call — proves the read happens only when the trigger says to. */
    @Volatile var readSourceIdCalls = 0
        private set

    override fun connActiveScids(conn: QuicheConn): Int {
        activeScidCalls++
        return activeScidCount
    }

    /**
     * Reports a count and writes **nothing**, for the reason [connDrainRetiredScids] does: this stub
     * decodes no real memory, and filling the driver's buffer with fabricated connection IDs would be
     * a lie dressed as coverage. What it models is the *policy* — when the driver reads, and what it
     * does with a count that disagrees with [connActiveScids].
     *
     * The bytes are covered where they mean something: `ConnectionIdSlotDecodeTests` against a
     * hand-built buffer, and `SourceIdReadbackTestSuite` against real quiche on every platform.
     */
    override fun connReadSourceIds(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int {
        readSourceIdCalls++
        return sourceIdsYielded
    }

    /**
     * Scripted `quiche_conn_path_event_next` queue, drained one event per call in FIFO order.
     *
     * **Empty by default**, so [connPathEventNext] answers `null` — "no events" — exactly as it always
     * has, and no existing driver test sees a path event appear.
     */
    val pathEvents: ArrayDeque<StubPathEvent> = ArrayDeque()

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        val event = pathEvents.removeFirstOrNull() ?: return null
        // Real quiche fills the caller's out-buffer with the event's local sockaddr, and the driver then
        // decodes THAT pointer to find which path the event is about. Model it by registering the
        // out-pointer under the event's port, so `decodePathKey(localOut)` yields the same PathKey the
        // probed path was opened with. Writing bytes would be a lie — this stub decodes no real memory.
        registerSockAddr(localOut, event.localPort)
        return event.type
    }

    private companion object {
        /**
         * RFC 9000 §5.1.1's initial destination CID sequence. The answer for a `connMigrate` naming a
         * local address that was never probed — a migrate onto the path the connection already lives
         * on, which is the only way to reach it.
         */
        const val INITIAL_DCID_SEQ = 0L

        /** Live-iterator handles: distinct so [streamIterNext] knows which queue it is draining. */
        const val READABLE_ITER = 2L
        const val WRITABLE_ITER = 1L

        /** The v4 address every registered synthetic sockaddr claims; only the port distinguishes them. */
        const val LOOPBACK_V4 = 0x7F000001L
    }
}

/**
 * One entry in [StubQuicheApi.pathEvents]: what quiche reports, and for which local port.
 *
 * [localPort] is how the event is tied to a path — it is what the stub decodes back out of the
 * driver's out-buffer, so it must match the port the path's sockaddr was registered under.
 */
internal class StubPathEvent(
    val type: QuichePathEventType,
    val localPort: Int,
)
