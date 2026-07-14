@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.posix.close
import platform.posix.memset
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import kotlin.concurrent.AtomicInt

/**
 * Apple (K/N) **unconnected** [DatagramChannel] backed by blocking POSIX `recvfrom`/`sendto` — the lift
 * of the quiche `AppleUdpServerChannel`, reshaped to the public datagram trichotomy. Per-packet source
 * is recovered from `recvfrom` and surfaced as [Datagram.peer]; the quiche `lastDest` cache is dropped
 * (the send target materializes from [SocketAddress] primitives into a `memScoped` scratch, RFC §4).
 *
 * `recvfrom` blocks, so it runs on a dedicated single-thread dispatcher; [close] closes the fd (which
 * unblocks any in-flight `recvfrom`). The recv sockaddr scratch is per-call (`memScoped`), so a
 * concurrent [close] never races a shared write buffer. Not thread-safe: confine [receive]/[send] each
 * to one coroutine (buffer-flow contract).
 *
 * Control plane: the rich Darwin POSIX ceiling (`IP_TOS`/`IP_DONTFRAG`/`IP_RECVTOS`/`IP_PKTINFO`) is a
 * labeled follow-up; this first landing advertises [DatagramCapabilities.None] (honest — the datapath
 * uses plain `recvfrom`/`sendto` with no ancillary data), so every read field is a sentinel and every
 * advisory send field a no-op.
 */
@ExperimentalDatagramApi
internal class PosixUdpDatagramChannel(
    private val fd: Int,
    override val localAddress: SocketAddress?,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
) : DatagramChannel {
    private val closedFlag = AtomicInt(0)
    private val recvDispatcher = newSingleThreadContext("apple-udp-recv-$fd")

    override val isOpen: Boolean get() = closedFlag.value == 0

    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    override val capabilities: DatagramCapabilities = DatagramCapabilities.None

    override suspend fun receive(): DatagramReadResult {
        val payload = bufferFactory.allocate(receiveBufferSize)
        val ptr = payload.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        try {
            while (true) {
                if (closedFlag.value != 0) {
                    payload.freeNativeMemory()
                    return DatagramReadResult.Closed()
                }
                val outcome: DatagramReadResult? =
                    memScoped {
                        val addr = alloc<sockaddr_storage>()
                        val addrLen = alloc<UIntVar>() // socklen_t is uint32 on Darwin (no socklen_tVar alias)
                        memset(addr.ptr, 0, sizeOf<sockaddr_storage>().convert())
                        addrLen.value = sizeOf<sockaddr_storage>().convert()
                        val n =
                            withContext(recvDispatcher) {
                                recvfrom(fd, ptr, payload.capacity.convert(), 0, addr.ptr.reinterpret(), addrLen.ptr)
                                    .toInt()
                            }
                        when {
                            closedFlag.value != 0 -> DatagramReadResult.Closed()
                            n >= 0 -> {
                                val peer = sockaddrToAppleSocketAddress(addr.ptr.reinterpret<sockaddr>())
                                if (peer == null) {
                                    null // unroutable family — skip, keep waiting
                                } else {
                                    payload.position(0)
                                    payload.setLimit(n)
                                    DatagramReadResult.Received(Datagram(payload = payload, peer = peer))
                                }
                            }
                            else -> DatagramReadResult.Closed(reason = n)
                        }
                    }
                if (outcome is DatagramReadResult.Received) return outcome
                if (outcome is DatagramReadResult.Closed) {
                    payload.freeNativeMemory()
                    return outcome
                }
                // outcome == null → retry with the same payload buffer.
            }
        } catch (t: Throwable) {
            payload.freeNativeMemory()
            throw t
        }
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        check(closedFlag.value == 0) { "sink is closed" }
        checkNotNull(to) { "no destination: an unconnected UDP channel requires a non-null `to`" }
        val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
        val ptr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
        val len = payload.remaining()
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val addrLen = to.writeSockaddr(addr)
            sendto(fd, ptr, len.convert(), 0, addr.ptr.reinterpret(), addrLen)
        }
    }

    override fun close() {
        if (!closedFlag.compareAndSet(0, 1)) return
        // Close the fd first — this unblocks any in-flight recvfrom (returns -1) so the receive loop
        // returns Closed. The recv scratch is per-call memScoped, so nothing shared is freed here.
        close(fd)
        runCatching { recvDispatcher.close() }
    }

    companion object {
        private const val MAX_UDP_PAYLOAD = 65507
    }
}
