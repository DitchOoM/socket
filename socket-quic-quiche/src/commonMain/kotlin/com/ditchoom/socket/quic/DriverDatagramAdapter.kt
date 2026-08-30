@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.withContext

/**
 * Exposes a [QuicheDriver]'s RFC-9221 unreliable datagrams as a buffer-flow [ConnectedDatagramChannel]
 * — the *connected* (single-peer) datagram endpoint backing [QuicScope.datagramChannel]. This is the Phase-7
 * fold of the old `QuicScope.sendDatagram`/`receiveDatagram` surface onto the shared datagram
 * trichotomy; every received [Datagram] carries the connection's [remote] peer.
 *
 * Shared by every quiche-backed platform connection (JVM/Android/Linux/Apple) so the buffer-ownership
 * and native-lifetime rules live in one place. The logic mirrors [DriverStreamAdapter] for streams:
 *
 * - **receive**: acquire from the driver's per-connection [QuicheDriver.recvBufPool] (the caller's
 *   `freeNativeMemory()` recycles the buffer back to that pool), let quiche write into it, transfer
 *   ownership to the caller on success and free it otherwise. A [NonCancellable] join on the in-flight
 *   command guarantees quiche has finished writing `addr` before the buffer can be released (the
 *   read-after-free guard from [DriverStreamAdapter]).
 * - **send**: the caller owns the buffer; the driver only reads its native address. The same in-flight
 *   join guarantees quiche finished reading before the caller frees/recycles it.
 *
 * A QUIC datagram flow has one implicit peer (the connected refinement has no destination parameter)
 * and no per-datagram IP control plane, so [send] ignores its `options` argument, [capabilities]
 * advertises nothing but the native-memory requirement the send path imposes, and [close] is a no-op
 * (the connection owns the datagram flow's lifecycle).
 */
internal class DriverDatagramAdapter(
    private val driver: QuicheDriver,
    private val remote: SocketAddress,
) : ConnectedDatagramChannel {
    /**
     * The structured close reason if the connection has closed, else one built from [fallback].
     *
     * Delegates to [QuicheDriver.closeReasonOr] rather than reading `driver.state` itself: the driver's
     * KDoc names connection state the single source of truth for a close reason, and two copies of that
     * resolution can drift.
     */
    private fun closedReason(fallback: QuicError): QuicCloseReason = driver.closeReasonOr(fallback)

    /**
     * The close reason folded to a bare [QuicError], for [DatagramReadResult.Closed] — buffer-flow's
     * datagram result predates [QuicCloseReason] and carries an error, not a side.
     */
    private fun closedError(fallback: QuicError): QuicError = closedReason(fallback).errorOrNull ?: fallback

    override val isOpen: Boolean
        get() = driver.state.value !is QuicConnectionState.Closed

    /** The connection's fixed remote endpoint — every datagram here is to/from this peer. */
    override val peer: SocketAddress get() = remote

    /** QUIC does not surface the underlying UDP endpoint — the typed absent state, never `null`. */
    override val localAddress: LocalAddress = LocalAddress.Unknown

    /**
     * QUIC application datagrams carry no raw IP control plane (ECN/DF/PKTINFO) — but [send] hands
     * quiche the payload's raw address, so the one data-plane requirement is stated (#502): the same
     * answer as the connection's [QuicScope.capabilities], in buffer-flow's vocabulary. This used to be
     * [DatagramCapabilities.None], which that type's own doc calls a *real claim* that heap payloads are
     * sendable — made over a `nativeMemoryAccess!!`.
     */
    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(requiresNativeMemoryBuffers = QuicheDriver.capabilities.requiresNativeMemoryBuffers)

    override val maxWritableSize: Int
        get() =
            when (val max = driver.lastMaxDatagramSize) {
                is MaxDatagramSize.Bytes -> max.bytes
                is MaxDatagramSize.Unavailable -> 0
            }

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        val remaining = payload.remaining()
        when (val max = driver.lastMaxDatagramSize) {
            is MaxDatagramSize.Unavailable ->
                throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
            is MaxDatagramSize.Bytes ->
                require(remaining <= max.bytes) { "datagram too large: $remaining > ${max.bytes} bytes" }
        }
        // A zero-length datagram is valid (RFC 9221); a 0-remaining buffer may not expose a native
        // address, so pass a null pointer in that case (the backends send NULL/len 0). Otherwise this is
        // a caller-fed buffer (#502): a heap payload is rejected by type before anything is enqueued —
        // see DriverStreamAdapter.streamWrite, the stream half of the same contract.
        val addr =
            if (remaining > 0) {
                val native =
                    payload.nativeMemoryAccess
                        ?: throw QuicNativeMemoryRequiredException.forBuffer(
                            QuicWriteTarget.Datagram,
                            payload,
                            QuicheDriver.capabilities,
                        )
                native.nativeAddress + payload.position()
            } else {
                0L
            }

        // See DriverStreamAdapter.streamWrite: keep the buffer alive until any in-flight send finishes
        // reading `addr`, since the caller frees it the instant we return.
        var inFlight: CompletableDeferred<Int>? = null
        try {
            while (true) {
                val deferred = CompletableDeferred<Int>()
                // trySend, not send — see DriverStreamAdapter.streamRead: atomic enqueue-or-throw,
                // no buffered-yet-cancelled state that could skip the finally's join on `inFlight`.
                driver.commands.trySend(QuicheCmd.DgramSend(addr, remaining, deferred)).getOrThrow()
                inFlight = deferred
                val written = deferred.await()
                inFlight = null
                when {
                    // All-or-nothing: quiche accepted the whole datagram (returns its length, 0 for empty).
                    written >= 0 -> return
                    // Send queue full — backpressure. Park until flushOutgoing drains it, then retry.
                    written == QuicheDriver.QUICHE_ERR_DONE -> driver.dgramWritableSignal.receive()
                    else ->
                        throw QuicCloseException(
                            closedReason(QuicError.InternalError("quiche datagram send error: $written")),
                            "quiche datagram send error: $written",
                        )
                }
            }
        } catch (_: ClosedSendChannelException) {
            throw QuicCloseException(closedReason(QuicError.NoError), "connection closed", attribution = driver.closeAttribution())
        } catch (_: ClosedReceiveChannelException) {
            // dgramWritableSignal was closed by cleanup() — the connection went away while parked.
            throw QuicCloseException(closedReason(QuicError.NoError), "connection closed", attribution = driver.closeAttribution())
        } finally {
            inFlight?.let { withContext(NonCancellable) { it.join() } }
        }
    }

    override suspend fun receive(): DatagramReadResult {
        val buffer = driver.recvBufPool.allocate(QuicheDriver.MAX_DATAGRAM_SIZE)
        val addr = buffer.driverOwnedNativeAddress()

        // See DriverStreamAdapter.streamRead: the driver may still be writing into `addr` inside
        // connDgramRecv when we unwind, so wait (non-cancellably) for any in-flight recv before freeing.
        var inFlight: CompletableDeferred<StreamRecvResult>? = null
        var transferred = false
        try {
            while (true) {
                val deferred = CompletableDeferred<StreamRecvResult>()
                // trySend, not send — same atomic enqueue-or-throw as DgramSend above.
                driver.commands.trySend(QuicheCmd.DgramRecv(addr, QuicheDriver.MAX_DATAGRAM_SIZE, deferred)).getOrThrow()
                inFlight = deferred
                val result = deferred.await()
                inFlight = null
                when (result) {
                    is StreamRecvResult.Data -> {
                        // bytesRead may be 0 — a valid empty datagram. Ownership transfers either way.
                        buffer.position(result.bytesRead)
                        buffer.resetForRead()
                        transferred = true
                        return DatagramReadResult.Received(
                            Datagram(
                                payload = buffer,
                                peer = remote,
                                ecn = Ecn.Unknown,
                                localAddress = LocalAddress.Unknown,
                                hopLimit = HopLimit.Unknown,
                            ),
                        )
                    }
                    is StreamRecvResult.Done -> driver.dgramSignal.receive() // park until one arrives, then retry
                    is StreamRecvResult.Error -> return DatagramReadResult.Closed(reason = closedError(QuicError.NoError))
                    // Datagrams have no stream to reset. Reset -> here would mean a backend bug (quiche
                    // decoded a stream-only sentinel out of a dgram recv); ConnectionGone is the old -2
                    // teardown sentinel. Both land on the same verdict as any other Error above.
                    is StreamRecvResult.Reset, is StreamRecvResult.ConnectionGone ->
                        return DatagramReadResult.Closed(reason = closedError(QuicError.NoError))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            DatagramReadResult.Closed(reason = closedError(QuicError.NoError))
        } catch (_: ClosedSendChannelException) {
            return DatagramReadResult.Closed(reason = closedError(QuicError.NoError))
        } catch (_: ClosedReceiveChannelException) {
            return DatagramReadResult.Closed(reason = closedError(QuicError.NoError))
        } finally {
            inFlight?.let { withContext(NonCancellable) { it.join() } }
            if (!transferred) buffer.freeNativeMemory()
        }
    }

    /** The QUIC connection owns the datagram flow's lifecycle; closing the channel alone is a no-op. */
    override fun close() = Unit
}
