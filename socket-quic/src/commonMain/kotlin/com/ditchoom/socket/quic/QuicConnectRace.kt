package com.ditchoom.socket.quic

import com.ditchoom.socket.AttemptVerdict
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.candidatesFor
import com.ditchoom.socket.connectRace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration

/**
 * A raced connect's two answers: the connection that won, and what became of every candidate.
 *
 * The race outcome travels **beside** the connection rather than on it. Wrapping the connection to
 * carry it would hide the backend's own type behind a decorator, and a consumer that reaches its driver
 * by `is`-check — which is how the trace tap and the migration wiring reach it — would silently stop
 * finding it.
 */
class QuicRacedConnect(
    val connection: QuicConnection,
    val candidateRace: QuicCandidateRace,
)

/**
 * Open a QUIC connection to [peer], racing its candidate endpoints and keeping the first handshake
 * that completes.
 *
 * This is the client entry every other one funnels through — [withQuicConnection], `QuicTransport`,
 * and an application dialling a peer whose candidates it gathered itself. [QuicEngine.connect] to a
 * single endpoint stays the backend's job; the race, the resolution above it and the teardown of
 * every candidate that loses are the same on every backend and live here, so a backend cannot get
 * them subtly differently.
 *
 * Pacing comes from [TransportConfig.connectPacing]: RFC 8305 §5 staggered by default, and
 * [Simultaneous][com.ditchoom.socket.ConnectPacing.Simultaneous] for a peer-to-peer connect whose
 * first flights are also NAT punches.
 *
 * Extensions rather than members so no engine can override them: every engine races the same way, or
 * the guarantee that a losing candidate is torn down would be per-backend folklore.
 */
suspend fun QuicEngine.connectRacing(
    binding: QuicClientBinding,
    peer: QuicPeer,
    quicOptions: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicRacedConnect {
    val candidates =
        when (peer) {
            is QuicPeer.Named ->
                transport.nameResolution.candidatesFor(peer.hostname).map { QuicEndpoint(it, peer.port) }
            is QuicPeer.Candidates -> peer.endpoints
        }
    if (candidates.size == 1) {
        // Not a race, and not run as one: a single candidate through the racer would pay for a pacer
        // and an exhaustion collector to decide something already decided.
        val only = candidates.single()
        return QuicRacedConnect(
            connect(binding, only, peer.serverName, quicOptions, transport, timeout),
            QuicCandidateRace.Unopposed(only),
        )
    }
    return race(binding, candidates, peer.serverName, quicOptions, transport, timeout)
}

/** [connectRacing] for a caller that wants the connection and not the reckoning. */
suspend fun QuicEngine.connect(
    binding: QuicClientBinding,
    peer: QuicPeer,
    quicOptions: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnection = connectRacing(binding, peer, quicOptions, transport, timeout).connection

/**
 * What a failed QUIC connect attempt says about the peer's *other* candidates.
 *
 * Every candidate is the same peer presenting the same certificate, so a handshake the peer's identity
 * failed cannot come out differently at another address: retrying it would turn one honest certificate
 * error into a list of them and delay the report by the whole candidate set. Anything else — a
 * blackhole, a refusal, a timeout, a transport error — is about the path, and another candidate may
 * well work.
 *
 * A certificate-hash pin that did not match already reports itself as
 * [com.ditchoom.socket.ConnectionFailureReason.TlsBadCertificate], so [AttemptVerdict.of] calls it
 * fatal without help; what needs saying here is that quiche reports the same class of failure as a
 * TLS alert inside a [QuicCloseException].
 */
fun quicConnectVerdict(error: Throwable): AttemptVerdict =
    when {
        error is QuicCloseException && error.quicError.isAboutThePeersIdentity() -> AttemptVerdict.Fatal
        else -> AttemptVerdict.of(error)
    }

/**
 * TLS alert codes (RFC 8446 §6.2) that reject who the peer *is* rather than how it was reached.
 *
 * The certificate family plus the two decisions the peer makes about the client: `access_denied`
 * (the certificate is valid and access control refused it) and `no_application_protocol` (RFC 7301 —
 * the peer speaks none of the offered ALPNs). Neither changes at a different address of the same peer.
 */
private object PeerIdentityAlert {
    const val BAD_CERTIFICATE = 42
    const val UNSUPPORTED_CERTIFICATE = 43
    const val CERTIFICATE_REVOKED = 44
    const val CERTIFICATE_EXPIRED = 45
    const val CERTIFICATE_UNKNOWN = 46
    const val UNKNOWN_CA = 48
    const val ACCESS_DENIED = 49
    const val BAD_CERTIFICATE_HASH_VALUE = 114
    const val CERTIFICATE_REQUIRED = 116
    const val NO_APPLICATION_PROTOCOL = 120

    val codes =
        setOf(
            BAD_CERTIFICATE,
            UNSUPPORTED_CERTIFICATE,
            CERTIFICATE_REVOKED,
            CERTIFICATE_EXPIRED,
            CERTIFICATE_UNKNOWN,
            UNKNOWN_CA,
            ACCESS_DENIED,
            BAD_CERTIFICATE_HASH_VALUE,
            CERTIFICATE_REQUIRED,
            NO_APPLICATION_PROTOCOL,
        )
}

private fun QuicError.isAboutThePeersIdentity(): Boolean = this is QuicError.CryptoError && tlsAlert in PeerIdentityAlert.codes

/** One candidate's attempt, carrying the slot it has to report its own fate into. */
private class Attempted(
    val index: Int,
    val connection: QuicConnection,
)

/**
 * What became of one candidate, before it is paired with the endpoint it belongs to. Separate from
 * [QuicCandidateLoss] only so a fate can be recorded by the attempt, which knows its index and not its
 * endpoint; [at] is the one place the two are joined.
 */
private sealed interface Fate {
    fun at(endpoint: QuicEndpoint): QuicCandidateLoss

    data object NeverAttempted : Fate {
        override fun at(endpoint: QuicEndpoint) = QuicCandidateLoss.NeverAttempted(endpoint)
    }

    data object Abandoned : Fate {
        override fun at(endpoint: QuicEndpoint) = QuicCandidateLoss.Abandoned(endpoint)
    }

    data object ClosedAsLate : Fate {
        override fun at(endpoint: QuicEndpoint) = QuicCandidateLoss.ClosedAsLate(endpoint)
    }

    data class HandshakeFailed(
        val error: Throwable,
    ) : Fate {
        override fun at(endpoint: QuicEndpoint) = QuicCandidateLoss.HandshakeFailed(endpoint, error)
    }
}

private suspend fun QuicEngine.race(
    binding: QuicClientBinding,
    candidates: List<QuicEndpoint>,
    serverName: String,
    quicOptions: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicRacedConnect {
    // One slot per candidate, written only by that candidate's own attempt and read only after the race
    // has joined every attempt — so this needs no lock and no atomic, and there is no slot two
    // coroutines can disagree about.
    val fates = MutableList<Fate>(candidates.size) { Fate.NeverAttempted }
    val winner =
        connectRace(
            candidates = candidates.indices.toList(),
            pacing = transport.connectPacing,
            verdict = ::quicConnectVerdict,
            close = { late ->
                fates[late.index] = Fate.ClosedAsLate
                late.connection.close()
            },
            attempt = { index ->
                try {
                    Attempted(
                        index,
                        connect(binding, candidates[index], serverName, quicOptions, transport, timeout),
                    )
                } catch (e: Throwable) {
                    // A cancelled attempt did not fail: another candidate won while this one was still
                    // handshaking, and its own teardown ran on the way out. Reporting that as a failure
                    // would put a fabricated error in the outcome.
                    fates[index] = if (currentCoroutineContext().isActive) Fate.HandshakeFailed(e) else Fate.Abandoned
                    throw e
                }
            },
        )
    val lost = candidates.indices.filter { it != winner.index }.map { fates[it].at(candidates[it]) }
    return QuicRacedConnect(winner.connection, QuicCandidateRace.Raced(candidates[winner.index], lost))
}
