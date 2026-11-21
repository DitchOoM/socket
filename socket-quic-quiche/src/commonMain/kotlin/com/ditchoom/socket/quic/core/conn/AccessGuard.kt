package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.core.sched.SendRecord
import com.ditchoom.socket.quic.core.udp.AddressReach
import com.ditchoom.socket.quic.core.udp.AttachedPrefix
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.reachFrom

/** Android's local-network permission, as it reads now. */
internal enum class PermissionRead {
    Held,
    Withheld,

    /** No application `Context` was captured to read it with. */
    Unread,
}

/**
 * How the system on this platform withholds the local network from an app, and so what a refused connect or handshake
 * send says about it. Chosen once per platform; the newest systems have one, older ones are [Unguarded].
 */
internal sealed interface AccessGuard {
    /** Android 17 (API 37) and later: `ACCESS_LOCAL_NETWORK`, enforced by the kernel refusing the send (`EPERM`). */
    class AndroidPermission(
        val permission: () -> PermissionRead,
        val attached: () -> List<AttachedPrefix>,
    ) : AccessGuard

    /**
     * macOS 15 and later: Local Network privacy, which refuses a connect or send to the local network at once with
     * `EHOSTUNREACH`. A JVM reads every no-path errno as one, so a refusal of that family judges, and only for a peer on
     * an attached network: such a peer has a route, and the interface's going down removes it from [attached].
     */
    class MacosPrivacy(
        val attached: () -> List<AttachedPrefix>,
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

/** The platform's guard, chosen once. */
internal expect val accessGuardOfThisPlatform: AccessGuard

/** What the system refusing a connect or every handshake send to [peer], the last with [refusal], says about access. */
internal fun AccessGuard.judge(
    refusal: SendRefusal,
    peer: PathKey,
): AccessVerdict =
    when (this) {
        is AccessGuard.AndroidPermission ->
            when (refusal) {
                is SendRefusal.PermissionDenied -> androidVerdict(permission(), peer, attached)
                is SendRefusal.TooLarge, is SendRefusal.NetworkUnreachable, is SendRefusal.HostUnreachable,
                is SendRefusal.PortUnreachable, is SendRefusal.NoBufferSpace, is SendRefusal.Unnamed,
                -> AccessVerdict.NotDenied
            }
        is AccessGuard.MacosPrivacy ->
            when (refusal) {
                is SendRefusal.HostUnreachable, is SendRefusal.NetworkUnreachable ->
                    when (peer.reachFrom(attached())) {
                        AddressReach.OnLink -> AccessVerdict.Denied(QuicAccessDenial.MacosNoRouteOnLink)
                        AddressReach.Loopback, AddressReach.Private, AddressReach.Beyond -> AccessVerdict.NotDenied
                    }
                is SendRefusal.TooLarge, is SendRefusal.PermissionDenied, is SendRefusal.PortUnreachable,
                is SendRefusal.NoBufferSpace, is SendRefusal.Unnamed,
                -> AccessVerdict.NotDenied
            }
        is AccessGuard.Unguarded -> AccessVerdict.NotDenied
    }

/**
 * A refused send is the denial when the permission is withheld: the kernel's filter enforces it and the device decides
 * which addresses are local. Unread, it is the likely denial when the peer is on the local network by any reading.
 */
private fun androidVerdict(
    permission: PermissionRead,
    peer: PathKey,
    attached: () -> List<AttachedPrefix>,
): AccessVerdict =
    when (permission) {
        PermissionRead.Withheld -> AccessVerdict.Denied(QuicAccessDenial.AndroidPermissionNotHeld)
        PermissionRead.Held -> AccessVerdict.NotDenied
        PermissionRead.Unread ->
            when (peer.reachFrom(attached())) {
                AddressReach.OnLink, AddressReach.Private -> AccessVerdict.Denied(QuicAccessDenial.AndroidSendsNotPermitted)
                AddressReach.Loopback, AddressReach.Beyond -> AccessVerdict.NotDenied
            }
    }

/** What a connection's handshake sends to [peer] say about the app's access, through [guard]. */
internal class PeerAccess(
    private val guard: AccessGuard,
    private val peer: PathKey,
) {
    /** Only a handshake whose every datagram the network refused can have been denied. */
    fun judge(record: SendRecord): AccessVerdict =
        when (record) {
            is SendRecord.Refused -> guard.judge(record.last, peer)
            is SendRecord.Quiet, is SendRecord.Taking -> AccessVerdict.NotDenied
        }
}
