@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import kotlin.time.Instant

// A real certificate is a few KiB and a real chain three or four deep; the caps only stop a hostile peer
// from making this client allocate without bound. Over either, the connect fails closed.
private const val INITIAL_CERT_CAPACITY = 4096
private const val MAX_CERT_CAPACITY = 1 shl 16 // 64 KiB
private const val MAX_CHAIN_LENGTH = 16

/**
 * A trust store consulted outside BoringSSL with the server's whole chain: Security.framework's SecTrust
 * on iOS/tvOS/watchOS ([AppleTrustSource.SystemTrustStore]). Returns `null` when [chain] (DER, leaf first)
 * is trusted for [serverName], else the platform's own account of why not.
 */
internal fun interface SystemChainTrust {
    suspend fun evaluate(
        serverName: String,
        chain: List<ReadBuffer>,
    ): String?
}

/**
 * The post-handshake half of #186, shared by every quiche client connect path (JVM/Android, Linux, Apple):
 * read the server's certificate chain through the driver and put it to [systemTrust] (the platform store,
 * where BoringSSL is not the one validating) and then to [verifier] (the caller's
 * [QuicOptions.serverCertVerifier]). Runs after establishment and before the connection is handed to the
 * caller, so a rejected server has received no application data. No-op when there is neither.
 *
 * On any rejection or failure it [closeConnection]s and throws — [ServerCertificateRejectedException] for a
 * verdict — so an unverified connection is never returned. A resumed connection ([resumed]) whose session
 * carries no chain is accepted without one: its trust was settled by the connection that issued the
 * ticket, which ran this same check.
 *
 * [readChainDer] has [readPeerCertDerThroughDriver]'s snprintf contract for chain position `index`: the
 * DER length, copied only when it fits; 0 past the end of the chain.
 */
internal suspend fun verifyServerCertificateChain(
    serverName: String,
    resumed: Boolean,
    systemTrust: SystemChainTrust?,
    verifier: ServerCertVerifier?,
    bufferFactory: BufferFactory,
    readChainDer: suspend (index: Int, der: PlatformBuffer, capacity: Int) -> Int,
    closeConnection: suspend () -> Unit,
    now: Instant,
) {
    if (systemTrust == null && verifier == null) return
    val chain = mutableListOf<PlatformBuffer>()
    try {
        readChain(bufferFactory, readChainDer, chain)
        if (chain.isEmpty()) {
            if (resumed) return
            throw ServerCertificateRejectedException(ServerCertificateRejection.NoPeerCertificate)
        }
        systemTrust?.evaluate(serverName, chain.map { it.slice() })?.let { detail ->
            throw ServerCertificateRejectedException(ServerCertificateRejection.UntrustedBySystem(serverName, detail))
        }
        if (verifier != null) {
            when (val verdict = verifier.verify(PeerCertificateChain(serverName, chain.map { it.slice() }, now))) {
                ServerCertVerdict.Trusted -> Unit
                is ServerCertVerdict.Rejected ->
                    throw ServerCertificateRejectedException(ServerCertificateRejection.RejectedByVerifier(verdict.reason))
            }
        }
    } catch (t: Throwable) {
        // Rejected, or the read itself failed: never return an unverified connection.
        runCatching { closeConnection() }
        throw t
    } finally {
        chain.forEach { it.freeNativeMemory() }
    }
}

/** Read every certificate of the chain into [into], each positioned for reading. */
private suspend fun readChain(
    bufferFactory: BufferFactory,
    readChainDer: suspend (index: Int, der: PlatformBuffer, capacity: Int) -> Int,
    into: MutableList<PlatformBuffer>,
) {
    for (index in 0 until MAX_CHAIN_LENGTH + 1) {
        var capacity = INITIAL_CERT_CAPACITY
        while (true) {
            val der = bufferFactory.allocate(capacity)
            val len =
                try {
                    readChainDer(index, der, capacity)
                } catch (t: Throwable) {
                    der.freeNativeMemory()
                    throw t
                }
            when {
                len <= 0 -> {
                    der.freeNativeMemory()
                    return
                }
                len > capacity -> {
                    der.freeNativeMemory()
                    if (len > MAX_CERT_CAPACITY) {
                        throw ServerCertificateRejectedException(ServerCertificateRejection.CertificateTooLarge(len, MAX_CERT_CAPACITY))
                    }
                    capacity = len
                }
                else -> {
                    if (index == MAX_CHAIN_LENGTH) {
                        der.freeNativeMemory()
                        throw ServerCertificateRejectedException(ServerCertificateRejection.ChainTooLong(MAX_CHAIN_LENGTH))
                    }
                    der.position(len)
                    der.resetForRead()
                    into += der
                    break
                }
            }
        }
    }
}
