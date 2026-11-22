package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.quic.HandshakeBudget
import com.ditchoom.socket.quic.PeerCertificateChain
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.replay.conditions.ReplayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What a connect came to, as the virtual connect tests compare it. */
internal const val RETURNED = "returned"
internal const val FAILED = "failed:"
internal const val REFUSED = "refused:"

/** A connect after its connection started, exactly as `coreConnect` makes it: [ServerCheck.connected]; [outcome] once done. */
internal class Connect {
    var outcome = ""

    fun done() = outcome.isNotEmpty()
}

/** The client's check, as `coreConnect` builds it from the connection's parts. */
internal fun ReplayClient.check(): ServerCheck = ServerCheck(connection, parts.conn, parts.chain)

/** Launches [client]'s connect with [options] (their resumption, against the client's offer), on the pair's scope. */
internal fun CoroutineScope.connectOf(
    client: ReplayClient,
    options: QuicOptions,
): Connect {
    val connect = Connect()
    launch {
        connect.outcome =
            try {
                val budget = HandshakeBudget(10.seconds, TimeSource.Monotonic.markNow())
                client.check().connected(options.resumption, client.offer, "localhost", options, 10.seconds, budget)
                RETURNED
            } catch (failed: QuicCloseException) {
                "$FAILED ${failed.closeReason} ${failed.circumstance}"
            } catch (refused: ServerCertificateRejectedException) {
                "$REFUSED ${refused.rejection}"
            } catch (refused: CertificateHashPinningException) {
                "$REFUSED ${refused.failure}"
            }
    }
    return connect
}

/** A verifier that answers [verdict] and records what it was handed. */
internal class RecordingVerifier(
    private val verdict: ServerCertVerdict,
) : ServerCertVerifier {
    var calls = 0
        private set
    var leafBytes = 0
        private set

    override suspend fun verify(chain: PeerCertificateChain): ServerCertVerdict {
        calls++
        leafBytes = chain.leaf.remaining()
        return verdict
    }
}
