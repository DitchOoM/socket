package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/**
 * The public [QuicPeerLeaf] of a leaf certificate's DER: its SHA-256 in memory the garbage collector frees, since the
 * value outlives the connection that read it. [der] is read in place and not moved.
 */
internal fun leafOf(der: ReadBuffer): QuicPeerLeaf {
    val digest = BufferFactory.managed().allocate(SHA256_BYTES)
    sha256Into(der, digest)
    digest.resetForRead()
    return QuicPeerLeaf.Presented(CertificateHash(digest))
}

private const val SHA256_BYTES = 32
