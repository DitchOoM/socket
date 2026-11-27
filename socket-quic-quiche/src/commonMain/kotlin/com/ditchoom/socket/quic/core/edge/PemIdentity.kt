package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.QuicTlsConfig

/** A server's certificate chain and private key as PEM held in memory, and the keys it encrypts session tickets with. */
internal class PemIdentity(
    val certificateChain: ReadBuffer,
    val privateKey: ReadBuffer,
    val sessionTicketKeys: QuicSessionTicketKeys,
)

/**
 * Writes [certificatePem] and [privateKeyPem] to files only the current user can read, runs [block] with their paths and
 * deletes both when it returns or throws. Neither buffer is consumed.
 */
internal expect suspend fun <R> withOwnerOnlyPemFiles(
    certificatePem: ReadBuffer,
    privateKeyPem: ReadBuffer,
    block: suspend (QuicTlsConfig) -> R,
): R

/** Runs [block], which binds a server, with this identity on owner-only files that are gone once [block] returns. */
internal suspend fun <R> PemIdentity.onFiles(block: suspend (QuicTlsConfig) -> R): R =
    withOwnerOnlyPemFiles(certificateChain, privateKey) { files -> block(files.copy(sessionTicketKeys = sessionTicketKeys)) }
