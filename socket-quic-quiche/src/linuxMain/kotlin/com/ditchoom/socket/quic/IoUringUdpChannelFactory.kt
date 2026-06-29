@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.socket_getsockname
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AI_PASSIVE
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.memcpy
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * Linux/Native [UdpChannelFactory]: opens a new io_uring UDP socket bound to a chosen local
 * address and connected to the same peer as the originating connection, for active connection
 * migration (RFC 9000 §9). Mirrors the primary-path socket setup in `withQuicConnection`
 * (linux) and the JVM [NioUdpChannelFactory].
 *
 * [peerSockAddrAddress]/[peerSockAddrLen] point at the originating connection's pinned peer
 * sockaddr (kept alive for the driver's life via its `onCleanup`), so we connect each new path
 * to the same peer without re-resolving DNS.
 */
internal class IoUringUdpChannelFactory(
    private val peerSockAddrAddress: Long,
    private val peerSockAddrLen: Int,
    private val bufferFactory: BufferFactory,
) : UdpChannelFactory {
    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "Failed to create migration path UDP socket" }

        try {
            memScoped {
                // Resolve the local bind address. AI_PASSIVE + null host = wildcard; an explicit
                // host (e.g. a loopback alias 127.0.0.2) binds that source. Port 0 → ephemeral.
                val hints = alloc<addrinfo>()
                hints.ai_family = AF_INET
                hints.ai_socktype = SOCK_DGRAM
                hints.ai_flags = AI_PASSIVE
                val bindResult = alloc<CPointerVar<addrinfo>>()
                val gaiRc = getaddrinfo(localHost, localPort.toString(), hints.ptr, bindResult.ptr)
                check(gaiRc == 0) { "Failed to resolve migration local bind addr $localHost:$localPort (rc=$gaiRc)" }
                try {
                    val bindAddr = bindResult.value!!.pointed
                    val bindRc = bind(fd, bindAddr.ai_addr, bindAddr.ai_addrlen)
                    check(bindRc == 0) { "Failed to bind migration path to $localHost:$localPort (rc=$bindRc)" }
                } finally {
                    freeaddrinfo(bindResult.value)
                }

                // Connect to the same peer as the originating connection.
                val peerPtr = peerSockAddrAddress.toCPointer<sockaddr>()!!
                val connectRc = connect(fd, peerPtr, peerSockAddrLen.convert())
                check(connectRc == 0) { "Failed to connect migration path to peer (rc=$connectRc)" }

                // Read back the resolved local 4-tuple (the ephemeral port quiche must validate).
                val localAddr = alloc<sockaddr_in>()
                platform.posix.memset(localAddr.ptr, 0, sizeOf<sockaddr_in>().convert())
                val localAddrLen = alloc<UIntVar>()
                localAddrLen.value = sizeOf<sockaddr_in>().convert()
                val gsRc = socket_getsockname(fd, localAddr.ptr.reinterpret(), localAddrLen.ptr)
                check(gsRc == 0) { "socket_getsockname on migration path returned $gsRc" }

                // Pin the local sockaddr in a heap buffer for quiche's probe/migrate + recv_info.
                val localSockAddrLen = sizeOf<sockaddr_in>().toInt()
                val localSockAddrBuf = bufferFactory.allocate(localSockAddrLen)
                val dst = localSockAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                memcpy(dst, localAddr.ptr, localSockAddrLen.convert())
                localSockAddrBuf.resetForRead()

                return NewPath(
                    channel = IoUringUdpChannel(fd),
                    localSockAddrAddress = localSockAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                    localSockAddrLength = localSockAddrLen,
                    release = { localSockAddrBuf.freeNativeMemory() },
                )
            }
        } catch (t: Throwable) {
            close(fd)
            throw t
        }
    }
}
