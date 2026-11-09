@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_LINGER
import platform.posix.connect
import platform.posix.errno
import platform.posix.linger
import platform.posix.memset
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket

internal actual suspend fun connectRawTcpPeer(port: Int): RawTcpPeer =
    withContext(Dispatchers.IO) {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: errno $errno" }
        val connected =
            memScoped {
                val addr = alloc<sockaddr_in>()
                memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                fillLoopbackV4(addr, port)
                connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
        if (connected != 0) {
            val error = errno
            platform.posix.close(fd)
            error("connect(127.0.0.1:$port) failed: errno $error")
        }
        PosixRawTcpPeer(fd)
    }

private class PosixRawTcpPeer(
    private val fd: Int,
) : RawTcpPeer {
    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            bytes.usePinned { pinned ->
                val sent = send(fd, pinned.addressOf(0), bytes.size.convert(), 0)
                check(sent.toInt() == bytes.size) { "send() wrote $sent of ${bytes.size}: errno $errno" }
            }
        }
    }

    override suspend fun close() {
        platform.posix.close(fd)
    }

    override suspend fun reset() {
        memScoped {
            val abortive = alloc<linger>()
            abortive.l_onoff = 1
            abortive.l_linger = 0
            setsockopt(fd, SOL_SOCKET, SO_LINGER, abortive.ptr, sizeOf<linger>().convert())
        }
        platform.posix.close(fd)
    }
}

/** 127.0.0.1:[port] in network byte order. */
private fun fillLoopbackV4(
    addr: sockaddr_in,
    port: Int,
) {
    addr.sin_family = AF_INET.convert()
    addr.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
    addr.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1, little-endian host
}
