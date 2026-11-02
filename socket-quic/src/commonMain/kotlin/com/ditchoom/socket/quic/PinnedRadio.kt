package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkKind
import kotlin.time.Duration

/** Why a [MigrationPolicy.Pinned] connect was refused before any socket opened. */
sealed interface PinnedRadioRefusal {
    /** This engine cannot pin a socket to the radio on this platform — see [EngineCapabilities.pinnableRadios]. */
    data object EngineCannotPin : PinnedRadioRefusal

    /** The connection rides a port someone else owns ([QuicClientBinding.Shared]); its socket is not this connection's to pin. */
    data object SharedPort : PinnedRadioRefusal
}

/**
 * A connect pinned to [radio] that cannot be pinned, for [refusal]. Raised before any socket opens, and
 * identically at every candidate endpoint, so a raced connect reports it once.
 */
class PinnedRadioRefusedException(
    val radio: NetworkKind.Radio,
    val refusal: PinnedRadioRefusal,
) : UnsupportedOperationException("a connection cannot be pinned to $radio here: $refusal")

/** Why a [MigrationPolicy.Pinned] connection could not open its path on its radio. */
sealed interface PinnedRadioUnavailable {
    /** The platform produced no network on the radio within [bound]: it is off, or out of range. Android. */
    data class NotAttached(
        val bound: Duration,
    ) : PinnedRadioUnavailable

    /**
     * The platform refused to request the radio's network; [cause] is its exception. Android, when the
     * merged manifest lacks `android.permission.CHANGE_NETWORK_STATE`.
     */
    data class NotPermitted(
        val cause: Throwable,
    ) : PinnedRadioUnavailable

    /** No application `Context` was captured, so there is no `ConnectivityManager` to ask. Android. */
    data object NoPlatformContext : PinnedRadioUnavailable

    /**
     * No interface of the radio offers a path to the peer: the radio is not up, or it does not carry the
     * peer's address family. [cause] is the socket's typed refusal. Apple, where Network.framework
     * reports the path unsatisfied at once.
     */
    data class NoPath(
        val cause: Throwable,
    ) : PinnedRadioUnavailable
}

/** A connect pinned to [radio] that found no path on it, for [reason]. */
class PinnedRadioUnavailableException(
    val radio: NetworkKind.Radio,
    val reason: PinnedRadioUnavailable,
) : RuntimeException(
        "no path on $radio: $reason",
        when (reason) {
            is PinnedRadioUnavailable.NoPath -> reason.cause
            is PinnedRadioUnavailable.NotPermitted -> reason.cause
            is PinnedRadioUnavailable.NotAttached, PinnedRadioUnavailable.NoPlatformContext -> null
        },
    )

/**
 * Refuse a connect under [options] that this engine cannot pin, before anything is resolved or opened.
 * Every connect entry calls it — the racing entry before it resolves a name, and each engine's own
 * single-endpoint `connect` — so a direct engine call is refused the same way.
 */
fun EngineCapabilities.requirePinnable(
    binding: QuicClientBinding,
    options: QuicOptions,
) {
    val radio =
        when (val policy = options.migration) {
            is MigrationPolicy.Pinned -> policy.radio
            MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic -> return
        }
    if (radio !in pinnableRadios) throw PinnedRadioRefusedException(radio, PinnedRadioRefusal.EngineCannotPin)
    when (binding) {
        QuicClientBinding.OwnSocket -> Unit
        is QuicClientBinding.Shared -> throw PinnedRadioRefusedException(radio, PinnedRadioRefusal.SharedPort)
    }
}
