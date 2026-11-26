package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.withPosixPemFiles

internal actual suspend fun <R> withOwnerOnlyPemFiles(
    certificatePem: ReadBuffer,
    privateKeyPem: ReadBuffer,
    block: suspend (QuicTlsConfig) -> R,
): R = withPosixPemFiles(certificatePem, privateKeyPem, block)
