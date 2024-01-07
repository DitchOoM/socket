package com.ditchoom.socket

import kotlinx.cinterop.*
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.posix.*

class PosixServerSocket : ServerSocket {
    private val fileDescriptor: Int

    @OptIn(ExperimentalForeignApi::class)
    private val memScope = MemScope()

    init {
        fileDescriptor = socket(AF_INET, SOCK_STREAM, 0)
            .ensureUnixCallResult("socket") { !it.isMinusOne() }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun bind(port: Int, host: String?, backlog: Int): Flow<ClientSocket> {
        val actualPort = port.toShort()
        with(memScope) {
            val serverAddr = alloc<sockaddr_in>()
            with(serverAddr) {
                memset(this.ptr, 0, sockaddr_in.size.convert())
                sin_family = AF_INET.convert()
                sin_port = posix_htons(actualPort).convert()
            }
            bind(fileDescriptor, serverAddr.ptr.reinterpret(), sockaddr_in.size.convert())
                .ensureUnixCallResult("bind") { it == 0 }
        }
        listen(fileDescriptor, backlog.toInt())
            .ensureUnixCallResult("listen") { it == 0 }
        return callbackFlow {
            while (isOpen()) {
                trySendBlocking(accept()).getOrThrow()
            }
        }
    }

    override fun isListening(): Boolean = isOpen()

    @OptIn(ExperimentalForeignApi::class)
    fun accept(): ClientSocket {
        val acceptedClientFileDescriptor = accept(fileDescriptor, null, null)
            .ensureUnixCallResult("accept") { !it.isMinusOne() }
        val server2Client = PosixClientSocket()
        server2Client.currentFileDescriptor = acceptedClientFileDescriptor
        return server2Client
    }

    fun isOpen() = try {
        port()
        true
    } catch (e: Throwable) {
        false
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun port(): Int = memScoped {
        val localAddress = alloc<sockaddr_in>()
        val addressLength = alloc<socklen_tVar>()
        addressLength.value = sockaddr_in.size.convert()
        if (getsockname(fileDescriptor, localAddress.ptr.reinterpret(), addressLength.ptr) < 0) {
            -1
        } else {
            swapBytes(localAddress.sin_port.toInt())
        }
    }

    override suspend fun close() {
        close(fileDescriptor)
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun Int.ensureUnixCallResult(op: String, predicate: (Int) -> Boolean): Int {
        if (!predicate(this)) {
            throw Error("$op: ${strerror(posix_errno())!!.toKString()}")
        }
        return this
    }

    private fun Int.isMinusOne() = (this == -1)
}
