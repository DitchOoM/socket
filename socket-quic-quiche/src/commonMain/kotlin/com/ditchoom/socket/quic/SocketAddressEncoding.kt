@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.SocketAddressCodec

/**
 * A native C `sockaddr` materialized from a [SocketAddress] for a quiche FFI call. Holds the backing
 * native buffer so it can be [free]d once quiche no longer references the pointer.
 */
internal class EncodedSockAddr(
    private val buffer: PlatformBuffer,
    val address: Long,
    val length: Int,
) {
    fun free() = buffer.freeNativeMemory()
}

/**
 * Encode [addr] into a fresh native buffer as C `sockaddr` bytes for quiche's `recv_info` / `send_info` /
 * `connect` FFI, replacing the hand-rolled `SockAddrUtil.toNativeSockAddr` (JVM) and `writeSockaddr`
 * (native) byte-poking with the one differential-tested [SocketAddressCodec].
 *
 * The buffer comes from [bufferFactory] — the quic network factory forces native memory on every
 * platform (JVM DirectByteBuffer, K/N malloc), so [EncodedSockAddr.address] is a valid pointer.
 * `recv_info.from`/`to` store the *pointer*, so a connection's peer/local encodings must stay pinned
 * for the driver's life (freed via the driver's `onCleanup`); `connect` copies inline, so a transient
 * encoding suffices there.
 */
internal fun SocketAddressCodec.encodeToNative(
    addr: SocketAddress,
    bufferFactory: BufferFactory,
): EncodedSockAddr {
    val size = (wireSize(addr, EncodeContext.Empty) as WireSize.Exact).bytes
    val buffer = bufferFactory.allocate(size)
    encode(buffer, addr, EncodeContext.Empty)
    buffer.resetForRead()
    val address = buffer.nativeMemoryAccess!!.nativeAddress.toLong()
    return EncodedSockAddr(buffer, address, size)
}
