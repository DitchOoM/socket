package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.JcaPeerCertificateBackend
import com.ditchoom.socket.quic.QuicTlsConfig

internal actual suspend fun <R> withOwnerOnlyPemFiles(
    certificatePem: ReadBuffer,
    privateKeyPem: ReadBuffer,
    block: suspend (QuicTlsConfig) -> R,
): R = JcaPeerCertificateBackend.withPemFiles(certificatePem, privateKeyPem, block)
