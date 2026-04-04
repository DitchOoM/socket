package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = AppleQuicEngine()

/**
 * Apple QUIC engine using Network.framework.
 *
 * Zero-copy: Network.framework delivers data as NSData (dispatch_data_t),
 * wrapped in NSDataBuffer without copying. Sends accept NSData directly.
 *
 * iOS 15+ / macOS 12+ required for QUIC support.
 */
private class AppleQuicEngine : QuicEngine {
    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection {
        TODO(
            "Apple QUIC via Network.framework — NWConnection with QUIC parameters. " +
                "Zero-copy read path: NSData → NSDataBuffer. " +
                "Zero-copy write path: NSDataBuffer → dispatch_data_t.",
        )
    }

    override fun close() {}
}
