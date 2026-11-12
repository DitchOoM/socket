package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * A [QuicheApi] decorator that checks, before every call that takes a connection, a `recv_info` or a
 * `send_info`, that the caller holds the driver loop's turn (see [LoopGuard]). Debug builds and tests only:
 * [QuicheDriver] installs it when its [LoopGuard] is [LoopGuard.Checking], never in production, so a
 * production driver calls the backend directly.
 *
 * Every override is the same two lines, generated from [QuicheApi]'s declarations that mention
 * [QuicheConn], [QuicheRecvInfo] or [QuicheSendInfo]; a call that takes none of them (configuration,
 * packet parsing, sockaddr encoding) is not connection state and passes through `by delegate`. A new
 * [QuicheApi] method over a connection that is not listed here is passed through unchecked, which
 * `LoopGuardCoverageTests` turns into a failure.
 */
internal class GuardedQuicheApi(
    private val delegate: QuicheApi,
    private val guard: LoopGuard,
) : QuicheApi by delegate {
    override fun connFree(conn: QuicheConn) {
        guard.assertOnLoop(LoopSite.Quiche)
        delegate.connFree(conn)
    }

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connRecv(conn, buf, bufLen, recvInfo)
    }

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSend(conn, buf, bufLen, sendInfo)
    }

    override fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connStreamRecv(conn, streamId, buf, bufLen)
    }

    override fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connStreamSend(conn, streamId, buf, bufLen, fin)
    }

    override fun connStreamCapacity(
        conn: QuicheConn,
        streamId: QuicStreamId,
    ): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connStreamCapacity(conn, streamId)
    }

    override fun connPeerStreamsLeftBidi(conn: QuicheConn): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPeerStreamsLeftBidi(conn)
    }

    override fun connPeerStreamsLeftUni(conn: QuicheConn): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPeerStreamsLeftUni(conn)
    }

    override fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connStreamShutdown(conn, streamId, direction, err)
    }

    override fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connDgramSend(conn, buf, bufLen)
    }

    override fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connDgramRecv(conn, buf, bufLen)
    }

    override fun hasReadableDgram(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.hasReadableDgram(conn)
    }

    override fun connDgramMaxWritableLen(conn: QuicheConn): MaxDatagramSize {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connDgramMaxWritableLen(conn)
    }

    override fun connIsEstablished(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connIsEstablished(conn)
    }

    override fun connIsHandshakeConfirmed(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connIsHandshakeConfirmed(conn)
    }

    override fun connIsClosed(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connIsClosed(conn)
    }

    override fun connIsTimedOut(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connIsTimedOut(conn)
    }

    override fun connSetSession(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSetSession(conn, buf, bufLen)
    }

    override fun connSession(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSession(conn, buf, bufLen)
    }

    override fun connIsResumed(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connIsResumed(conn)
    }

    override fun connIsInEarlyData(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connIsInEarlyData(conn)
    }

    override fun connEarlyDataReason(conn: QuicheConn): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connEarlyDataReason(conn)
    }

    override fun connStreamDataUnacknowledged(conn: QuicheConn): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connStreamDataUnacknowledged(conn)
    }

    override fun connTimeout(conn: QuicheConn): Duration? {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connTimeout(conn)
    }

    override fun connOnTimeout(conn: QuicheConn) {
        guard.assertOnLoop(LoopSite.Quiche)
        delegate.connOnTimeout(conn)
    }

    override fun connSendAckEliciting(conn: QuicheConn): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSendAckEliciting(conn)
    }

    override fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connClose(conn, error)
    }

    override fun connPeerCert(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPeerCert(conn, buf, bufLen)
    }

    override fun connPeerCertChainAt(
        conn: QuicheConn,
        index: Int,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPeerCertChainAt(conn, index, buf, bufLen)
    }

    override fun connApplicationProto(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connApplicationProto(conn, buf, bufLen)
    }

    override fun connTraceId(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connTraceId(conn, buf, bufLen)
    }

    override fun connSourceId(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSourceId(conn, buf, bufLen)
    }

    override fun connPeerError(conn: QuicheConn): QuicError? {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPeerError(conn)
    }

    override fun connLocalError(conn: QuicheConn): QuicError? {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connLocalError(conn)
    }

    override fun connStats(conn: QuicheConn): QuicConnStats? {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connStats(conn)
    }

    override fun connPathStats(
        conn: QuicheConn,
        pathIdx: Long,
    ): QuicPathStats? {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPathStats(conn, pathIdx)
    }

    override fun connPeerTransportParams(conn: QuicheConn): PeerTransportParams {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPeerTransportParams(conn)
    }

    override fun connSetQlogPath(
        conn: QuicheConn,
        path: String,
        title: String,
        desc: String,
    ): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSetQlogPath(conn, path, title, desc)
    }

    override fun connSetKeylogPath(
        conn: QuicheConn,
        path: String,
    ): Boolean {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connSetKeylogPath(conn, path)
    }

    override fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): ProbeOutcome {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connProbePath(conn, localAddr, localLen, peerAddr, peerLen)
    }

    override fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connNewScid(conn, scidAddr, scidLen, resetTokenAddr, retireIfNeeded, seqOut)
    }

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): MigrateOutcome {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connMigrate(conn, localAddr, localLen, peerAddr, peerLen)
    }

    override fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connMigrateSource(conn, localAddr, localLen, seqOut)
    }

    override fun connAvailableDcids(conn: QuicheConn): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connAvailableDcids(conn)
    }

    override fun connRetireDcid(
        conn: QuicheConn,
        dcidSeq: Long,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connRetireDcid(conn, dcidSeq)
    }

    override fun connScidsLeft(conn: QuicheConn): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connScidsLeft(conn)
    }

    override fun connRetiredScids(conn: QuicheConn): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connRetiredScids(conn)
    }

    override fun connDrainRetiredScids(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connDrainRetiredScids(conn, out, maxIds)
    }

    override fun connActiveScids(conn: QuicheConn): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connActiveScids(conn)
    }

    override fun connReadSourceIds(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connReadSourceIds(conn, out, maxIds)
    }

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connPathEventNext(conn, localOut, localLenOut, peerOut, peerLenOut)
    }

    override fun connReadable(conn: QuicheConn): QuicheStreamIter {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connReadable(conn)
    }

    override fun connWritable(conn: QuicheConn): QuicheStreamIter {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.connWritable(conn)
    }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        guard.assertOnLoop(LoopSite.Quiche)
        delegate.recvInfoFree(info)
    }

    override fun sendInfoFree(info: QuicheSendInfo) {
        guard.assertOnLoop(LoopSite.Quiche)
        delegate.sendInfoFree(info)
    }

    override fun sendInfoToAddr(info: QuicheSendInfo): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.sendInfoToAddr(info)
    }

    override fun sendInfoToAddrLen(info: QuicheSendInfo): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.sendInfoToAddrLen(info)
    }

    override fun sendInfoFromAddr(info: QuicheSendInfo): Long {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.sendInfoFromAddr(info)
    }

    override fun sendInfoFromAddrLen(info: QuicheSendInfo): Int {
        guard.assertOnLoop(LoopSite.Quiche)
        return delegate.sendInfoFromAddrLen(info)
    }
}
