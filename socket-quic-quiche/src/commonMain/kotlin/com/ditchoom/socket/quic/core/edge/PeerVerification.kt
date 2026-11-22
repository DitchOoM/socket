package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PlatformChainTrust
import com.ditchoom.socket.quic.QuicConfigCalls
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.conn.ChainSource
import com.ditchoom.socket.quic.platformChainTrust

/**
 * The calls a client config is built with from [options]: the shared option calls, with quiche's own chain check
 * off where the platform's store checks the chain after the handshake instead.
 */
internal fun applyClientOptions(
    options: QuicOptions,
    calls: QuicConfigCalls,
) {
    applyQuicOptions(options, calls, QuicRole.Client)
    when (platformChainTrust(options)) {
        is PlatformChainTrust.InHandshake -> Unit
        is PlatformChainTrust.AfterHandshake -> calls.verifyPeer(false)
    }
}

/** Copied at completion only when [ServerCheck] will read it: a pin, a verifier, or the platform's store after the handshake. */
internal fun chainSourceOf(
    options: QuicOptions,
    factory: BufferFactory,
): ChainSource {
    val read =
        options.serverCertificateHashes.isNotEmpty() ||
            options.serverCertVerifiers.isNotEmpty() ||
            platformChainTrust(options) is PlatformChainTrust.AfterHandshake
    return if (read) ChainSource.Copied(factory) else ChainSource.NotAsked
}
