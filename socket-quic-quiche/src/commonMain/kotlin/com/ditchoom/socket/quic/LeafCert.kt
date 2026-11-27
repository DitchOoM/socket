package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/**
 * The leaf certificate a completed handshake copied out of quiche, in memory the garbage collector frees, since it
 * outlives the connection that read it. It is hashed when the public facts are made, on the caller's thread: the loop
 * that copies it runs no digest.
 */
internal sealed interface LeafCert {
    /** The peer presented none, or quiche could not hand it over. */
    data object NotPresented : LeafCert

    class Copied(
        val der: ReadBuffer,
    ) : LeafCert
}

/** A copy of [der]'s remaining bytes; [der] is read in place and not moved. */
internal fun copiedLeaf(der: ReadBuffer): LeafCert {
    val view = der.slice()
    val copy = BufferFactory.managed().allocate(view.remaining())
    copy.write(view)
    copy.resetForRead()
    return LeafCert.Copied(copy)
}

/** The public [QuicPeerLeaf]: the SHA-256 of the copied DER. */
internal fun LeafCert.toPublic(): QuicPeerLeaf =
    when (this) {
        is LeafCert.NotPresented -> QuicPeerLeaf.NotPresented
        is LeafCert.Copied -> {
            // Apple's digest reads its input by address: the DER is hashed from native memory, freed at once.
            val view = der.slice()
            val native = BufferFactory.network().allocate(view.remaining())
            val digest = BufferFactory.managed().allocate(SHA256_BYTES)
            try {
                native.write(view)
                native.resetForRead()
                sha256Into(native, digest)
            } finally {
                native.freeNativeMemory()
            }
            digest.resetForRead()
            QuicPeerLeaf.Presented(CertificateHash(digest))
        }
    }

private const val SHA256_BYTES = 32
