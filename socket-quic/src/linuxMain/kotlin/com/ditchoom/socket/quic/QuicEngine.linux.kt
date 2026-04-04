package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = LinuxQuicEngine()

/**
 * Linux QUIC engine using quiche via cinterop + io_uring for UDP I/O.
 *
 * Zero-copy: buffer native addresses from [com.ditchoom.buffer.BufferFactory.deterministic]
 * are passed directly to quiche C functions via cinterop pointers.
 * Uses [com.ditchoom.buffer.nativeMemoryAccess] → `nativeAddress.toCPointer<ByteVar>()`
 * for the data path (connRecv, connSend, connStreamRecv, connStreamSend).
 *
 * UDP packets are sent/received via io_uring (reuses IoUringManager from base module).
 */
private class LinuxQuicEngine : QuicEngine {
    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection {
        TODO(
            "Linux QUIC via quiche cinterop + io_uring UDP. " +
                "Zero-copy path: nativeMemoryAccess.nativeAddress.toCPointer() → quiche_conn_stream_recv/send. " +
                "Pending: validate cinterop-generated function signatures against quiche 0.28.0 API.",
        )
    }

    override fun close() {}
}
