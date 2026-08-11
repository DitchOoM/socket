@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.bind
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.memcpy
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

/**
 * Minimal BSD-socket helpers for the Apple K/Native test proxies ([AppleQuicImpairmentTests],
 * [AppleQuicPassiveMigrationTests]) and the raw-datagram injector in [AppleQuicMalformedPacketTests].
 *
 * Linux's equivalents ride the repo's io_uring primitives (`IoUringUdpChannel` /
 * `IoUringUdpServerChannel`), which are linuxMain-only. Darwin has no io_uring, so these use plain
 * blocking `recvfrom`/`sendto` on a dedicated thread — the same shape `:socket-udp`'s Apple
 * `PosixUdpDatagramChannel` uses in production.
 *
 * Three Darwin details that differ from the Linux ports and are each easy to get wrong:
 *  - `htons`/`htonl`/`ntohs` are **macros** on Darwin, so K/N's posix package does not export them.
 *    Addresses are therefore written and read as explicit network-order **bytes** (the same technique
 *    `:socket-udp`'s `AppleSocketAddress` uses), which is also endian-independent by construction.
 *  - BSD `sockaddr_in` carries a leading `sin_len` byte and a **single-byte** `sin_family` at offset 1,
 *    where Linux has a two-byte `sa_family` at offset 0.
 *  - Darwin's K/N posix package has no `socklen_tVar` (socklen_t is a plain `uint32_t`), so the
 *    by-reference length argument is a [UIntVar].
 *
 * Every `ssize_t`-returning call is narrowed with `convert<Int>()` rather than `toInt()`: K/N maps
 * `ssize_t` to Long on all 64-bit Apple targets but to Int on arm64_32 (watchOS device), and `convert`
 * compiles on both. This module registers no watchOS target today, but the idiom costs nothing and is
 * the one that survives someone adding it.
 */
internal object ApplePosixUdp {
    const val MAX_DATAGRAM = 2048

    /** A UDP socket bound to an ephemeral 127.0.0.1 port, left unconnected so `recvfrom` yields sources. */
    fun openBoundLoopbackSocket(): Int {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "loopback UDP socket() failed" }
        memScoped {
            val addr = alloc<sockaddr_in>()
            writeLoopbackAddr(addr, port = 0)
            val rc = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(rc == 0) { "loopback UDP bind() failed (rc=$rc)" }
        }
        return fd
    }

    /** A UDP socket connected to `127.0.0.1:[port]`, so `send`/`recv` need no per-call address. */
    fun openConnectedLoopbackSocket(port: Int): Int {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "loopback UDP socket() failed" }
        memScoped {
            val addr = alloc<sockaddr_in>()
            writeLoopbackAddr(addr, port)
            val rc = connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(rc == 0) { "loopback UDP connect() failed (rc=$rc)" }
        }
        return fd
    }

    /** The ephemeral port [fd] actually got from `bind(…, 0)`. */
    fun boundPortOf(fd: Int): Int =
        memScoped {
            val addr = alloc<sockaddr_in>()
            val len = alloc<UIntVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            getsockname(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr)
            readPort(addr)
        }

    /**
     * Bound a blocking `recvfrom` so a pump loop can re-check its running flag.
     *
     * Belt-and-suspenders on top of "close the fd to unblock the receive": that is the documented Apple
     * teardown in `PosixUdpDatagramChannel`, but a *test* proxy that fails to wake would hang the suite
     * instead of failing it, which is the worst possible outcome to debug. With a receive timeout the
     * loop exits within one tick regardless, and the only cost is an idle wakeup every [millis].
     */
    fun setReceiveTimeout(
        fd: Int,
        millis: Long,
    ) {
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (millis / 1000).convert()
            tv.tv_usec = ((millis % 1000) * 1000).convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
    }

    /**
     * Blocking `recvfrom` into [into]; returns the byte count, or a negative value on error/timeout.
     * When [peer] is non-null the datagram's source is copied into it — the unconnected (client-facing)
     * side needs that to route replies back.
     */
    fun receiveFrom(
        fd: Int,
        into: ByteArray,
        peer: sockaddr_in? = null,
    ): Int =
        memScoped {
            val addr = alloc<sockaddr_in>()
            val addrLen = alloc<UIntVar>()
            addrLen.value = sizeOf<sockaddr_in>().convert()
            val n =
                into
                    .usePinned { pinned ->
                        recvfrom(fd, pinned.addressOf(0), into.size.convert(), 0, addr.ptr.reinterpret(), addrLen.ptr)
                    }.convert<Int>()
            if (n >= 0 && peer != null) memcpy(peer.ptr, addr.ptr, sizeOf<sockaddr_in>().convert())
            n
        }

    /** `send` on a connected socket. */
    fun sendConnected(
        fd: Int,
        bytes: ByteArray,
        length: Int = bytes.size,
    ): Int =
        // An empty datagram is legal UDP but `addressOf(0)` needs a non-empty backing array.
        (if (bytes.isEmpty()) ByteArray(1) else bytes)
            .usePinned { pinned -> send(fd, pinned.addressOf(0), length.convert(), 0) }
            .convert()

    /** `sendto` a pre-filled BSD address (the reply path of an unconnected socket). */
    fun sendTo(
        fd: Int,
        bytes: ByteArray,
        length: Int,
        dest: sockaddr_in,
    ): Int =
        (if (bytes.isEmpty()) ByteArray(1) else bytes)
            .usePinned { pinned ->
                sendto(fd, pinned.addressOf(0), length.convert(), 0, dest.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
            }.convert()

    /** One-shot `sendto` 127.0.0.1:[port] from a throwaway socket — the raw malformed-packet injector. */
    fun sendToLoopback(
        port: Int,
        bytes: ByteArray,
    ) {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "raw datagram socket() failed" }
        try {
            memScoped {
                val addr = alloc<sockaddr_in>()
                writeLoopbackAddr(addr, port)
                (if (bytes.isEmpty()) ByteArray(1) else bytes).usePinned { pinned ->
                    sendto(
                        fd,
                        pinned.addressOf(0),
                        bytes.size.convert(),
                        0,
                        addr.ptr.reinterpret<sockaddr>(),
                        sizeOf<sockaddr_in>().convert(),
                    )
                }
            }
        } finally {
            platform.posix.close(fd)
        }
    }

    /** Copy a whole BSD `sockaddr_in` (the recv scratch is reused, so a captured source must be copied). */
    fun copyAddr(
        from: sockaddr_in,
        to: sockaddr_in,
    ) {
        memcpy(to.ptr, from.ptr, sizeOf<sockaddr_in>().convert())
    }

    /**
     * Fill [addr] with `127.0.0.1:[port]` as raw BSD bytes: `sin_len`, single-byte `sin_family`, then
     * the port and address in network (big-endian) order. Written byte-wise rather than through the
     * struct fields because Darwin exports no `htons`/`htonl` to K/N — and because byte-wise is
     * endian-independent, so it stays correct on any host.
     */
    fun writeLoopbackAddr(
        addr: sockaddr_in,
        port: Int,
    ) {
        val bytes = addr.ptr.reinterpret<ByteVar>()
        val size = sizeOf<sockaddr_in>().toInt()
        for (i in 0 until size) bytes[i] = 0
        bytes[0] = size.toByte() // sin_len
        bytes[1] = (AF_INET and 0xFF).toByte() // sin_family — a single byte on BSD
        bytes[2] = ((port shr 8) and 0xFF).toByte() // sin_port, network order
        bytes[3] = (port and 0xFF).toByte()
        bytes[4] = 127 // sin_addr = 127.0.0.1, network order
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 1
    }

    /** The port of a kernel-filled BSD `sockaddr_in`, read from its network-order bytes. */
    fun readPort(addr: sockaddr_in): Int {
        val bytes = addr.ptr.reinterpret<ByteVar>()
        return ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
    }
}
