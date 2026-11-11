@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.SystemTrustFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Instant

// A real certificate is a few KiB and a real chain three or four deep; the caps only stop a hostile peer
// from making this client allocate without bound. Over either, the connect fails closed.
private const val INITIAL_CERT_CAPACITY = 4096
private const val MAX_CERT_CAPACITY = 1 shl 16 // 64 KiB
private const val MAX_CHAIN_LENGTH = 16

/** Where the server's chain is validated against a trust store. */
internal sealed interface PlatformChainTrust {
    /** BoringSSL validated it during the handshake. */
    data object InHandshake : PlatformChainTrust

    /** A store outside BoringSSL evaluates the whole chain (DER, leaf first) after the handshake. */
    class AfterHandshake(
        val evaluate: suspend (serverName: String, chain: List<ReadBuffer>) -> SystemTrustVerdict,
    ) : PlatformChainTrust
}

/** A platform trust store's answer for one chain. */
internal sealed interface SystemTrustVerdict {
    data object Trusted : SystemTrustVerdict

    data class Untrusted(
        val failure: SystemTrustFailure,
    ) : SystemTrustVerdict
}

/**
 * Read the server's chain through the driver and put it to [platformTrust], then to each of [verifiers],
 * before the connection is handed to the caller. A no-op when there is nothing to consult.
 *
 * On any rejection or failure it [closeConnection]s and throws, so an unverified connection is never
 * returned. A resumed connection whose session carries no chain is accepted: the connection that issued
 * its ticket ran this check.
 *
 * [readChainDer] has [readPeerCertDerThroughDriver]'s snprintf contract for chain position `index`.
 */
internal suspend fun verifyServerCertificateChain(
    serverName: String,
    resumption: QuicResumptionOutcome,
    platformTrust: PlatformChainTrust,
    verifiers: List<ServerCertVerifier>,
    bufferFactory: BufferFactory,
    readChainDer: suspend (index: Int, der: PlatformBuffer, capacity: Int) -> Int,
    closeConnection: suspend () -> Unit,
    now: Instant,
) {
    if (platformTrust is PlatformChainTrust.InHandshake && verifiers.isEmpty()) return
    val chain = mutableListOf<PlatformBuffer>()
    try {
        readChain(bufferFactory, readChainDer, chain)
        if (chain.isEmpty()) {
            when (resumption) {
                is QuicResumptionOutcome.Resumed -> return
                is QuicResumptionOutcome.FullHandshake ->
                    throw ServerCertificateRejectedException(ServerCertificateRejection.NoPeerCertificate)
            }
        }
        when (platformTrust) {
            PlatformChainTrust.InHandshake -> Unit
            is PlatformChainTrust.AfterHandshake ->
                when (val verdict = platformTrust.evaluateWithoutHoldingTheCaller(serverName, chain, bufferFactory)) {
                    SystemTrustVerdict.Trusted -> Unit
                    is SystemTrustVerdict.Untrusted ->
                        throw ServerCertificateRejectedException(
                            ServerCertificateRejection.UntrustedBySystem(serverName, verdict.failure),
                        )
                }
        }
        for (verifier in verifiers) {
            when (val verdict = verifier.verify(PeerCertificateChain(serverName, chain.map { it.slice() }, now))) {
                ServerCertVerdict.Trusted -> Unit
                is ServerCertVerdict.Rejected ->
                    throw ServerCertificateRejectedException(ServerCertificateRejection.RejectedByVerifier(verdict.reason))
            }
        }
    } catch (t: Throwable) {
        runCatching { closeConnection() }
        throw t
    } finally {
        chain.forEach { it.freeNativeMemory() }
    }
}

/**
 * Where an evaluation that outlives the connect that asked for it finishes. Nothing in it is ever
 * cancelled: an evaluation is a blocking platform call that cannot be interrupted, so the only choice
 * is whose thread waits for it.
 */
private val abandonedEvaluations = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Put [chain] to the platform trust store without making the connect wait on it uninterruptibly.
 *
 * A platform evaluator (iOS's `SecTrustEvaluateWithError`) blocks its thread while the OS fetches
 * revocation data or missing intermediates, and nothing can interrupt it. Awaited in the connect's own
 * coroutine, that call held the connect past its timeout and past its cancellation. So the evaluation
 * runs on its own, and the connect only awaits its verdict, which a cancellation or deadline leaves at
 * once. The evaluation then finishes on its own thread and its verdict is dropped.
 *
 * The evaluator is handed copies of the chain, freed when it returns: [chain] belongs to the caller,
 * who frees it as soon as this returns, which may be while the evaluation is still running.
 */
private suspend fun PlatformChainTrust.AfterHandshake.evaluateWithoutHoldingTheCaller(
    serverName: String,
    chain: List<PlatformBuffer>,
    bufferFactory: BufferFactory,
): SystemTrustVerdict {
    val owned =
        chain.map { der ->
            bufferFactory.allocate(der.remaining()).also {
                it.write(der.slice())
                it.resetForRead()
            }
        }
    val verdict = CompletableDeferred<SystemTrustVerdict>()
    abandonedEvaluations.launch {
        try {
            verdict.complete(evaluate(serverName, owned.map { it.slice() }))
        } catch (t: Throwable) {
            verdict.completeExceptionally(t)
        } finally {
            owned.forEach { it.freeNativeMemory() }
        }
    }
    return reportingStall(
        "the platform trust store's verdict on the server's chain",
        { "server=$serverName chain=${chain.size} certificate(s); the connect is waiting on a blocking platform call" },
    ) { verdict.await() }
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
