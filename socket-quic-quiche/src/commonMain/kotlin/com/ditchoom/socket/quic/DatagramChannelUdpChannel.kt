@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.awaitCancellation

/**
 * Adapts a `:socket-udp` buffer-flow [DatagramChannel] to quiche's internal [UdpChannel] seam (Phase 6,
 * adapter-first cutover). This is the **client / connected** shape shared by every platform: the JVM
 * (NIO), Linux (io_uring), and Apple (NWConnection) client datapaths all now open a `:socket-udp`
 * channel and wrap it here, replacing the three per-platform `*UdpChannel` clients with one common
 * adapter. The [QuicheDriver], [PathKey] routing, `recvBufPool`, migration, and every test double stay
 * unchanged — the driver still owns its pooled buffers; the adapter only bridges the two ownership
 * models (RFC §12 Phase 6, "Option A").
 *
 * ## Buffer ownership (zero-copy via [ownsReceiveBuffer])
 * The build site opens the `:socket-udp` channel with the driver's `recvBufPool` as its `bufferFactory`
 * (a [com.ditchoom.buffer.pool.BufferPool] *is* a [com.ditchoom.buffer.BufferFactory]), so each
 * [DatagramReadResult.Received] payload is *already* a pooled buffer. This adapter therefore reports
 * [ownsReceiveBuffer] = true and the driver's reader loop consumes [receiveOwned], handing that pooled
 * buffer straight to `quiche_conn_recv` with **no copy** — the buffer-flow allocate-and-transfer model
 * and quiche's pooled-buffer model line up on the same buffer. (The legacy caller-buffer [receive] path
 * is kept for completeness — it copies into the provided buffer — but the driver never calls it here.)
 * The channel is opened with a QUIC-sized staging buffer ([QuicheDriver.MAX_DATAGRAM_SIZE]) so a pool
 * miss allocates ~1350 bytes, not the 64 KB UDP ceiling, and matches the pool's buffer size.
 *
 * ## Destination
 * The client is a *connected* channel: the driver only supplies a non-null [PathKey] `dest` on the
 * **server** egress path, so this adapter always sends to the channel's fixed peer (`to = null`) and
 * ignores `dest`, exactly as the old connected `NioUdpChannel`/`IoUringUdpChannel` clients did. The
 * server egress adapter is a separate type (per platform), because turning a `PathKey` back into a
 * packed `SocketAddress` needs platform machinery this connected client never touches.
 */
internal class DatagramChannelUdpChannel(
    private val channel: DatagramChannel,
) : UdpChannel {
    override val ownsReceiveBuffer: Boolean = true

    override suspend fun receiveOwned(): OwnedDatagram =
        when (val result = channel.receive()) {
            is DatagramReadResult.Received -> {
                // The payload was allocated from the driver's recvBufPool (the channel's bufferFactory),
                // so it IS a pooled buffer — transfer it out as-is with no copy. Its window is [0, len)
                // and its native address is the datagram start (what quiche_conn_recv reads).
                val payload = result.datagram.payload
                OwnedDatagram(payload, payload.remaining())
            }
            is DatagramReadResult.Closed -> {
                // Permanently closed — park until the driver cancels this reader during teardown, exactly
                // as the caller-buffer [receive] path does, so the reader loop never busy-spins.
                awaitCancellation()
            }
        }

    override suspend fun receive(buffer: PlatformBuffer): Int =
        when (val result = channel.receive()) {
            is DatagramReadResult.Received -> {
                val payload = result.datagram.payload
                val length = payload.remaining()
                buffer.position(0)
                buffer.write(payload)
                // The channel allocated this payload and transferred ownership; free it now that its
                // bytes live in the driver's pooled buffer (no-op on GC platforms; frees on native).
                payload.freeNativeMemory()
                length
            }
            is DatagramReadResult.Closed -> {
                // The socket is gone. Returning <= 0 would spin the driver's reader loop (it treats a
                // non-positive receive as a transient timeout to retry). Park instead until the driver
                // cancels this reader during teardown — mirrors the Apple client's terminal park. If the
                // reader is already being cancelled (the common close path), awaitCancellation() throws
                // immediately and the loop unwinds with no busy-spin.
                awaitCancellation()
            }
        }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        // Present exactly [0, len) as the datagram window; the channel's send slices it non-destructively,
        // so the driver's reused send buffer is safe. `dest` is always null here (connected client).
        buffer.position(0)
        buffer.setLimit(len)
        channel.send(buffer, to = null)
    }

    override fun close() {
        channel.close()
    }
}
