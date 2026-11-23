package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.HandshakeBudget
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.verifyServerAfterHandshake
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * A client's check of its server once the handshake completed (see [verifyServerAfterHandshake]): against the chain the
 * handshake copied ([chain]), so a server that already closed is checked as one that has not, on the caller's context,
 * so a verifier never runs on the loop. A passed check lifts the hold on the connection's output.
 */
internal class ServerCheck(
    private val connection: CoreConnection,
    private val conn: Conn,
    private val chain: KeptChain,
) {
    /**
     * What a connect does once its connection started, the server's chain let go whatever happens: the handshake, the
     * server's check, and the [resumption]'s early-data block where [runEarlyData] runs it.
     */
    suspend fun connected(
        resumption: QuicResumption,
        offer: Offer,
        serverName: String,
        options: QuicOptions,
        timeout: Duration,
        budget: HandshakeBudget,
    ) {
        try {
            connection.runEarlyData(resumption, offer) {
                withTimeout(timeout) { connection.established() }
                verify(serverName, options, budget)
            }
        } finally {
            letGo()
        }
    }

    /** Refuses a server that fails the check with the alert its failure calls for; lets its held output go otherwise. */
    suspend fun verify(
        serverName: String,
        options: QuicOptions,
        budget: HandshakeBudget,
    ) {
        verifyServerAfterHandshake(
            serverName,
            options,
            connection.resumption,
            connection.bufferFactory,
            budget,
            chain::copy,
            connection::closeNow,
        )
        conn.submit(ServerChecked(), CallerPlace.HandToLoop)
    }

    /** The server's chain is freed, now or when it arrives. */
    fun letGo() = chain.letGo()
}

/** The server passed its check: the output held for it leaves. */
private class ServerChecked : ConnWork() {
    override fun run(inside: Inside) = inside.serverChecked()

    override fun gone(end: ConnEnd) = Unit
}
