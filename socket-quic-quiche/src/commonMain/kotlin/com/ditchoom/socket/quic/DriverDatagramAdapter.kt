package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Implements the [QuicScope] datagram surface (RFC 9221) on top of [QuicheDriver].
 *
 * Shared by every quiche-backed platform connection (JVM/Android/Linux) so the buffer-ownership and
 * native-lifetime rules live in one place. The logic mirrors [DriverStreamAdapter] for streams:
 *
 * - **receive**: acquire from the driver's per-connection [QuicheDriver.recvBufPool] (the caller's
 *   `freeNativeMemory()` recycles the buffer back to that pool), let quiche write into it, transfer
 *   ownership to the caller on success and free it otherwise. A [NonCancellable] join on the in-flight
 *   command guarantees quiche has finished writing `addr` before the buffer can be released (the
 *   read-after-free guard from [DriverStreamAdapter]).
 * - **send**: the caller owns the buffer; the driver only reads its native address. The same in-flight
 *   join guarantees quiche finished reading before the caller frees/recycles it.
 */
internal class DriverDatagramAdapter(
    private val driver: QuicheDriver,
) {
    /** The structured close reason if the connection has closed, else [fallback]. */
    private fun closedReason(fallback: QuicError): QuicError = (driver.state.value as? QuicConnectionState.Closed)?.error ?: fallback

    suspend fun sendDatagram(buffer: ReadBuffer) {
        val remaining = buffer.remaining()
        when (val max = driver.lastMaxDatagramSize) {
            is MaxDatagramSize.Unavailable ->
                throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
            is MaxDatagramSize.Bytes ->
                require(remaining <= max.bytes) { "datagram too large: $remaining > ${max.bytes} bytes" }
        }
        // A zero-length datagram is valid (RFC 9221); a 0-remaining buffer may not expose a native
        // address, so pass a null pointer in that case (the backends send NULL/len 0).
        val addr = if (remaining > 0) buffer.nativeMemoryAccess!!.nativeAddress.toLong() + buffer.position() else 0L

        // See DriverStreamAdapter.streamWrite: keep the buffer alive until any in-flight send finishes
        // reading `addr`, since the caller frees it the instant we return.
        var inFlight: CompletableDeferred<Int>? = null
        try {
            while (true) {
                val deferred = CompletableDeferred<Int>()
                driver.commands.send(QuicheCmd.DgramSend(addr, remaining, deferred))
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
            throw QuicCloseException(closedReason(QuicError.NoError), "connection closed")
        } catch (_: ClosedReceiveChannelException) {
            // dgramWritableSignal was closed by cleanup() — the connection went away while parked.
            throw QuicCloseException(closedReason(QuicError.NoError), "connection closed")
        } finally {
            inFlight?.let { withContext(NonCancellable) { it.join() } }
        }
    }

    suspend fun receiveDatagram(): DatagramReceiveResult {
        val buffer = driver.recvBufPool.allocate(QuicheDriver.MAX_DATAGRAM_SIZE)
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong()

        // See DriverStreamAdapter.streamRead: the driver may still be writing into `addr` inside
        // connDgramRecv when we unwind, so wait (non-cancellably) for any in-flight recv before freeing.
        var inFlight: CompletableDeferred<StreamRecvResult>? = null
        var transferred = false
        try {
            while (true) {
                val deferred = CompletableDeferred<StreamRecvResult>()
                driver.commands.send(QuicheCmd.DgramRecv(addr, QuicheDriver.MAX_DATAGRAM_SIZE, deferred))
                inFlight = deferred
                val result = deferred.await()
                inFlight = null
                when (result) {
                    is StreamRecvResult.Data -> {
                        // bytesRead may be 0 — a valid empty datagram. Ownership transfers either way.
                        buffer.position(result.bytesRead)
                        buffer.resetForRead()
                        transferred = true
                        return DatagramReceiveResult.Received(buffer)
                    }
                    is StreamRecvResult.Done -> driver.dgramSignal.receive() // park until one arrives, then retry
                    is StreamRecvResult.Error -> return DatagramReceiveResult.ConnectionClosed(closedReason(QuicError.NoError))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            DatagramReceiveResult.ConnectionClosed(closedReason(QuicError.NoError))
        } catch (_: ClosedSendChannelException) {
            return DatagramReceiveResult.ConnectionClosed(closedReason(QuicError.NoError))
        } catch (_: ClosedReceiveChannelException) {
            return DatagramReceiveResult.ConnectionClosed(closedReason(QuicError.NoError))
        } finally {
            inFlight?.let { withContext(NonCancellable) { it.join() } }
            if (!transferred) buffer.freeNativeMemory()
        }
    }

    /** Thin loop over [receiveDatagram] for parity with `streams()`; completes when the connection closes. */
    fun datagrams(): Flow<ReadBuffer> =
        flow {
            while (true) {
                when (val result = receiveDatagram()) {
                    is DatagramReceiveResult.Received -> emit(result.buffer)
                    is DatagramReceiveResult.ConnectionClosed -> return@flow
                }
            }
        }

    fun maxDatagramSize(): MaxDatagramSize = driver.lastMaxDatagramSize
}
