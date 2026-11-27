package com.ditchoom.socket.quic

import com.ditchoom.socket.AttemptVerdict
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.candidatesFor
import com.ditchoom.socket.connectRace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** A raced connect's two answers: the [connection] that won, and what became of every candidate. */
class QuicRacedConnect<out C>(
    val connection: C,
    val candidateRace: QuicCandidateRace,
)

/**
 * Connects to this peer by racing its candidate endpoints with [attempt], keeping the first that answers. [close]
 * releases a connection that answered after the race was decided. A [QuicPeer.Named] is resolved through
 * [TransportConfig.nameResolution]; candidates are paced by [TransportConfig.connectPacing], RFC 8305 section 5
 * staggered by default. A failure ends the race when [quicConnectVerdict] calls it fatal.
 *
 * [attempt] connects to exactly one endpoint, and a failure or a cancellation of it must leave nothing behind.
 */
suspend fun <C> QuicPeer.raceCandidates(
    transport: TransportConfig,
    close: suspend (C) -> Unit,
    attempt: suspend (QuicEndpoint) -> C,
): QuicRacedConnect<C> {
    val candidates =
        when (this) {
            is QuicPeer.Named -> transport.nameResolution.candidatesFor(hostname).map { QuicEndpoint(it, port) }
            is QuicPeer.Candidates -> endpoints
        }
    if (candidates.size == 1) {
        // Not a race, and not run as one: a pacer and an exhaustion collector would decide what is decided.
        val only = candidates.single()
        return QuicRacedConnect(attempt(only), QuicCandidateRace.Unopposed(only))
    }
    return race(candidates, transport, close, attempt)
}

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
 *
 * The other kind of failure no candidate can improve on is a rejected *argument*: every attempt is
 * built from the one [QuicOptions] the caller passed, so a connect it does not permit is refused
 * identically at every endpoint. [EarlyDataProtocolNotOfferedException] is that case — it is raised
 * before an attempt opens a socket, so retrying it would open none either, just report the same
 * programming error once per candidate. [QuicRefusedException] is another.
 *
 * A pinned radio the platform never produced ([PinnedRadioUnavailableException] for anything but
 * [PinnedRadioUnavailable.NoPath]) is the same radio at every endpoint, so it is fatal too; a path the
 * radio does not carry to one address may exist to another, so [PinnedRadioUnavailable.NoPath] is not.
 */
fun quicConnectVerdict(error: Throwable): AttemptVerdict =
    when {
        error is QuicCloseException && error.quicError.isAboutThePeersIdentity() -> AttemptVerdict.Fatal
        error is EarlyDataProtocolNotOfferedException -> AttemptVerdict.Fatal
        error is QuicRefusedException -> AttemptVerdict.Fatal
        error is PinnedRadioUnavailableException -> error.reason.verdict()
        else -> AttemptVerdict.of(error)
    }

private fun PinnedRadioUnavailable.verdict(): AttemptVerdict =
    when (this) {
        is PinnedRadioUnavailable.NoPath -> AttemptVerdict.TryOthers
        is PinnedRadioUnavailable.NotAttached, is PinnedRadioUnavailable.NotPermitted, PinnedRadioUnavailable.NoPlatformContext ->
            AttemptVerdict.Fatal
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
private class Attempted<C>(
    val index: Int,
    val connection: C,
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

private suspend fun <C> race(
    candidates: List<QuicEndpoint>,
    transport: TransportConfig,
    close: suspend (C) -> Unit,
    attempt: suspend (QuicEndpoint) -> C,
): QuicRacedConnect<C> {
    // One slot per candidate, written only by that candidate's own attempt and read only after the race has joined
    // every attempt: no slot two coroutines can disagree about.
    val fates = MutableList<Fate>(candidates.size) { Fate.NeverAttempted }
    val winner =
        connectRace(
            candidates = candidates.indices.toList(),
            pacing = transport.connectPacing,
            verdict = ::quicConnectVerdict,
            close = { late: Attempted<C> ->
                fates[late.index] = Fate.ClosedAsLate
                close(late.connection)
            },
            attempt = { index ->
                try {
                    Attempted(index, attempt(candidates[index]))
                } catch (e: Throwable) {
                    // A cancelled attempt did not fail: another candidate won while this one was still handshaking.
                    fates[index] = if (currentCoroutineContext().isActive) Fate.HandshakeFailed(e) else Fate.Abandoned
                    throw e
                }
            },
        )
    val lost = candidates.indices.filter { it != winner.index }.map { fates[it].at(candidates[it]) }
    return QuicRacedConnect(winner.connection, QuicCandidateRace.Raced(candidates[winner.index], lost))
}
