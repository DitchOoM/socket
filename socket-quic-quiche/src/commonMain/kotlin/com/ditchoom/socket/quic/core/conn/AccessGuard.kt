@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.sched.SendRecord
import com.ditchoom.socket.quic.core.udp.AddressReach
import com.ditchoom.socket.quic.core.udp.AttachedPrefix
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.reachFrom
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Android's local-network permission, as it reads now. */
internal enum class PermissionRead {
    Held,
    Withheld,

    /** No application `Context` was captured to read it with. */
    Unread,
}

/**
 * How the system on this platform withholds the local network from an app, and so what a refused connect or handshake
 * send says about it. Chosen once per platform; the newest systems have one, older ones are [Unguarded]. Everything it
 * reads is read on the setup lane, never on a loop.
 */
internal sealed interface AccessGuard {
    /**
     * Android 17 (API 37) and later: `ACCESS_LOCAL_NETWORK`, enforced by the kernel refusing the send (`EPERM`). Only a
     * peer on the local network is judged: Data Saver, a background firewall chain and VPN lockdown refuse as `EPERM` too.
     */
    class AndroidPermission(
        val permission: () -> PermissionRead,
        val attached: () -> List<AttachedPrefix>,
    ) : AccessGuard

    /**
     * macOS 15 and later: Local Network privacy, which refuses a connect or send to the local network with
     * `EHOSTUNREACH`. Only the route probe's connect is judged, and only the first one to a peer in this process: a
     * neighbour that stopped answering draws the same refusal of a new socket's first sends for a while, so no send is.
     */
    class MacosPrivacy(
        val attached: () -> List<AttachedPrefix>,
        val probed: ProbedPeers = ProbedPeers(),
    ) : AccessGuard

    /** Nothing withholds the local network from the sockets this core sends on, or the platform names a denial itself. */
    data object Unguarded : AccessGuard
}

/** What a refusal says about an app's access to the peer. */
internal sealed interface AccessVerdict {
    data object NotDenied : AccessVerdict

    class Denied(
        val denial: QuicAccessDenial,
    ) : AccessVerdict
}

/**
 * What a handshake whose every datagram the system refused says about the app's access to its peer: settled on the
 * setup lane, so a loop judges a refusal without reading the permission or the interfaces.
 */
internal sealed interface SendsAccess {
    /** No refusal denies: no guard judges sends here, the permission is held, or the peer is not on the local network. */
    data object NeverDenied : SendsAccess

    /** A refusal as not permitted is [denial]. */
    class NotPermittedIs(
        val denial: QuicAccessDenial,
    ) : SendsAccess
}

/** Whether a route probe is the first one to its peer in this process. */
internal enum class ProbeTurn { First, Again }

/** The peers this process has probed a route to, by address; once [limit] are kept, every probe counts as [ProbeTurn.Again]. */
@Published
internal class ProbedPeers(
    private val limit: Int = PROBED_PEERS_KEPT,
) {
    private val seen = AtomicReference<Set<PathKey>>(emptySet())

    /** Records a probe to [peer]; lock-free, from any setup lane. */
    fun probing(peer: PathKey): ProbeTurn {
        val address = PathKey(peer.family, 0, peer.hi, peer.lo)
        while (true) {
            val known = seen.load()
            if (address in known || known.size >= limit) return ProbeTurn.Again
            if (seen.compareAndSet(known, known + address)) return ProbeTurn.First
        }
    }
}

/** The platform's guard, chosen once. */
internal expect val accessGuardOfThisPlatform: AccessGuard

/** Setup lane, before the route probe to [peer]: whether it is the first to that peer, which only macOS judges by. */
internal fun AccessGuard.probing(peer: PathKey): ProbeTurn =
    when (this) {
        is AccessGuard.MacosPrivacy -> probed.probing(peer)
        is AccessGuard.AndroidPermission, is AccessGuard.Unguarded -> ProbeTurn.First
    }

/** Setup lane: what the system refusing the route probe's connect to [peer] with [refusal] says about access. */
internal fun AccessGuard.judgeProbe(
    refusal: SendRefusal,
    peer: PathKey,
    turn: ProbeTurn,
): AccessVerdict =
    when (this) {
        is AccessGuard.AndroidPermission ->
            when (val sends = sendsAccess(peer)) {
                is SendsAccess.NeverDenied -> AccessVerdict.NotDenied
                is SendsAccess.NotPermittedIs -> notPermitted(refusal, sends.denial)
            }
        is AccessGuard.MacosPrivacy ->
            when (refusal) {
                is SendRefusal.HostUnreachable, is SendRefusal.NetworkUnreachable ->
                    when (turn) {
                        ProbeTurn.First -> macosVerdict(peer.reachFrom(attached()))
                        ProbeTurn.Again -> AccessVerdict.NotDenied
                    }
                is SendRefusal.TooLarge, is SendRefusal.PermissionDenied, is SendRefusal.PortUnreachable,
                is SendRefusal.NoBufferSpace, is SendRefusal.Unnamed,
                -> AccessVerdict.NotDenied
            }
        is AccessGuard.Unguarded -> AccessVerdict.NotDenied
    }

/** Setup lane: what a handshake to [peer] whose every send is refused will say about access. */
internal fun AccessGuard.sendsAccess(peer: PathKey): SendsAccess =
    when (this) {
        is AccessGuard.AndroidPermission ->
            when (permission()) {
                PermissionRead.Held -> SendsAccess.NeverDenied
                PermissionRead.Withheld -> onTheLocalNetwork(peer, attached, QuicAccessDenial.AndroidPermissionNotHeld)
                PermissionRead.Unread -> onTheLocalNetwork(peer, attached, QuicAccessDenial.AndroidSendsNotPermitted)
            }
        is AccessGuard.MacosPrivacy, is AccessGuard.Unguarded -> SendsAccess.NeverDenied
    }

/** Only a handshake whose every datagram the network refused, the last as not permitted, can have been denied. */
internal fun SendsAccess.judge(record: SendRecord): AccessVerdict =
    when (this) {
        is SendsAccess.NeverDenied -> AccessVerdict.NotDenied
        is SendsAccess.NotPermittedIs ->
            when (record) {
                is SendRecord.Refused -> notPermitted(record.last, denial)
                is SendRecord.Quiet, is SendRecord.Taking -> AccessVerdict.NotDenied
            }
    }

private fun notPermitted(
    refusal: SendRefusal,
    denial: QuicAccessDenial,
): AccessVerdict =
    when (refusal) {
        is SendRefusal.PermissionDenied -> AccessVerdict.Denied(denial)
        is SendRefusal.TooLarge, is SendRefusal.NetworkUnreachable, is SendRefusal.HostUnreachable,
        is SendRefusal.PortUnreachable, is SendRefusal.NoBufferSpace, is SendRefusal.Unnamed,
        -> AccessVerdict.NotDenied
    }

/**
 * [denial] for a peer on the local network by any reading: on an attached prefix, or in a private range behind a
 * router. The interfaces are listed only for an address that is neither loopback nor already local by its range.
 */
private fun onTheLocalNetwork(
    peer: PathKey,
    attached: () -> List<AttachedPrefix>,
    denial: QuicAccessDenial,
): SendsAccess {
    val reach =
        when (val byRange = peer.reachFrom(emptyList())) {
            AddressReach.Beyond -> peer.reachFrom(attached())
            AddressReach.Loopback, AddressReach.OnLink, AddressReach.Private -> byRange
        }
    return when (reach) {
        AddressReach.OnLink, AddressReach.Private -> SendsAccess.NotPermittedIs(denial)
        AddressReach.Loopback, AddressReach.Beyond -> SendsAccess.NeverDenied
    }
}

private fun macosVerdict(reach: AddressReach): AccessVerdict =
    when (reach) {
        AddressReach.OnLink -> AccessVerdict.Denied(QuicAccessDenial.MacosNoRouteOnLink)
        AddressReach.Loopback, AddressReach.Private, AddressReach.Beyond -> AccessVerdict.NotDenied
    }

private const val PROBED_PEERS_KEPT = 1024
