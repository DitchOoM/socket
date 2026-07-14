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
 * ## Buffer ownership (the B2 bridge)
 * quiche's driver hands a pooled [PlatformBuffer] into [receive]; a buffer-flow channel instead
 * *allocates* the datagram payload and transfers ownership out. The adapter reconciles them with one
 * copy: read a [DatagramReadResult.Received], copy its payload into the driver's pooled buffer, then
 * free the channel-owned payload. The channel is opened with a QUIC-sized staging buffer
 * ([QuicheDriver.MAX_DATAGRAM_SIZE]) so that copy is from ~1350 bytes, not the 64 KB UDP ceiling.
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
