package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.HandshakeBudget
import com.ditchoom.socket.quic.PeerCertPosition
import com.ditchoom.socket.quic.PlatformChainTrust
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConfigCalls
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.platformChainTrust
import com.ditchoom.socket.quic.verifyServerAfterHandshake
import kotlinx.coroutines.CompletableDeferred

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

/**
 * Checks the server once the handshake completed and before the connect returns (see [verifyServerAfterHandshake]), on
 * the caller's context, so a verifier never runs on the loop. A refused connection never reaches the caller, so none
 * of its stream data does either.
 */
internal suspend fun CoreConnection.verifyServer(
    serverName: String,
    options: QuicOptions,
    budget: HandshakeBudget,
) = verifyServerAfterHandshake(serverName, options, resumption, bufferFactory, budget, ::peerCertificate, ::closeNow)

/** Reads one certificate of the peer's chain inside the connection; what the read throws fails only its caller. */
internal class PeerCertWork(
    private val position: PeerCertPosition,
    private val address: Long,
    private val capacity: Int,
    private val answer: CompletableDeferred<Int>,
) : ConnWork() {
    override fun run(inside: Inside) =
        guarded(answer::completeExceptionally) {
            answer.complete(inside.peerCertificate(position, address, capacity))
        }

    override fun gone(end: ConnEnd) {
        answer.completeExceptionally(endedWith(end, QuicCloseCircumstance.HandshakeFailed))
    }
}
