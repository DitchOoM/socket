package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.core.conn.Offer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Where every core connect's and serve's setup runs: opening sockets, reading trust anchors, loading the native library
 * and probing the route block, so none of it runs on a caller's thread, which may be a main thread. One setup at a time:
 * a herd of connects queues here and keeps one IO worker busy, instead of waking one worker per connect.
 */
internal val setupLane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/** A client connection set up and started on the [setupLane]; its handshake is still to complete. */
internal class StartedClient(
    val client: CoreClient,
    private val offer: Offer,
) {
    /**
     * On the caller's own context, so its early data never runs on the lane: parks until the handshake completes,
     * running the [resumption]'s early-data block where the offer says.
     */
    suspend fun established(
        resumption: QuicResumption,
        timeout: Duration,
    ): CoreClient {
        val connection = client.connection
        connection.runEarlyData(resumption, offer) { withTimeout(timeout) { connection.established() } }
        return client
    }

    /** Ends a connection whose connect failed or was cancelled after it started. */
    fun abandon() = client.connection.closeNow(QuicError.NoError)
}

/** How far a connect's setup got: what a failed or cancelled connect has to end. */
internal sealed interface ClientSetup {
    data object Unstarted : ClientSetup

    class Started(
        val started: StartedClient,
    ) : ClientSetup
}
