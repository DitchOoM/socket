@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_SNDBUF
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * The Apple [HostLoopback]: a bare BSD socket pair, none of `PosixUdpDatagramChannel` or
 * `NwUdpDatagramChannel` involved. `SO_SNDBUF` is widened to [MAX_UDP_DATAGRAM_SIZE] exactly as
 * `UdpSocket.widenSendBuffer` does — without it Darwin's 9216-byte default would have the probe report
 * a host ceiling of 9216 and skip the 65507 leg on the platform whose silent drop motivated this suite.
 */
internal actual val hostLoopback =
    HostLoopback { size ->
        withContext(Dispatchers.Default) { rawLoopbackCarries(size) }
    }

private fun rawLoopbackCarries(size: Int): Boolean =
    memScoped {
        val receiver = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        if (receiver < 0) return false
        val sender = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        if (sender < 0) {
            close(receiver)
            return false
        }
        try {
            setBufferSize(receiver, SO_RCVBUF, MAX_UDP_DATAGRAM_SIZE)
            setBufferSize(sender, SO_SNDBUF, MAX_UDP_DATAGRAM_SIZE)

            val addrLength = sizeOf<sockaddr_in>().toInt()
            val local = allocArray<ByteVar>(addrLength)
            writeLoopbackSockaddr(local, port = 0, length = addrLength)
            if (bind(receiver, local.reinterpret(), addrLength.convert()) != 0) return false

            val boundLength = alloc<socklen_tVar>()
            boundLength.value = addrLength.convert()
            if (getsockname(receiver, local.reinterpret(), boundLength.ptr) != 0) return false
            // BSD sockaddr_in: sin_len, sin_family, then the port in network order at offset 2.
            val port = ((local[2].toInt() and 0xFF) shl 8) or (local[3].toInt() and 0xFF)

            val target = allocArray<ByteVar>(addrLength)
            writeLoopbackSockaddr(target, port = port, length = addrLength)
            val payload = allocArray<ByteVar>(size)
            for (i in 0 until size) payload[i] = 0x41
            val sent = sendto(sender, payload, size.convert(), 0, target.reinterpret(), addrLength.convert())
            // .toLong() on BOTH sides: sendto returns ssize_t, which K/N maps to Long on every Apple
            // target except watchosArm64 (arm64_32), where it is Int. Comparing the raw return against
            // a Long compiles everywhere else and fails only there.
            if (sent.toLong() != size.toLong()) return false // the host refused it outright (EMSGSIZE)

            // Accepted — now find out whether it actually arrives. Silence here is the WSL2 shape: a
            // host that drops rather than refuses, and the case this whole seam exists to recognize.
            val fds = alloc<pollfd>()
            fds.fd = receiver
            fds.events = POLLIN.convert()
            fds.revents = 0
            if (poll(fds.ptr, 1.convert(), RECEIVE_TIMEOUT_MILLIS) <= 0) return false

            val landing = allocArray<ByteVar>(size + 1)
            recvfrom(receiver, landing, (size + 1).convert(), 0, null, null).toLong() == size.toLong()
        } finally {
            close(sender)
            close(receiver)
        }
    }

/** `sockaddr_in` for `127.0.0.1:[port]` in BSD layout: `sin_len`, 1-byte family, then network order. */
private fun writeLoopbackSockaddr(
    bytes: CPointer<ByteVar>,
    port: Int,
    length: Int,
) {
    for (i in 0 until length) bytes[i] = 0
    bytes[0] = (length and 0xFF).toByte() // sin_len — BSD only
    bytes[1] = (AF_INET and 0xFF).toByte()
    bytes[2] = ((port shr 8) and 0xFF).toByte()
    bytes[3] = (port and 0xFF).toByte()
    bytes[4] = 127
    bytes[5] = 0
    bytes[6] = 0
    bytes[7] = 1
}

private fun setBufferSize(
    fd: Int,
    option: Int,
    bytes: Int,
) = memScoped {
    val value = alloc<IntVar>()
    value.value = bytes
    setsockopt(fd, SOL_SOCKET, option, value.ptr, sizeOf<IntVar>().convert())
}

private const val RECEIVE_TIMEOUT_MILLIS = 1_000
