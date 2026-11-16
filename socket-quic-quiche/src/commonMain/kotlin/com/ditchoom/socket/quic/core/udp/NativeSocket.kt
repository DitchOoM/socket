package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.UdpBatching
import kotlin.concurrent.Volatile

/** How a socket's reads reach the loop: one datagram per message, or split from coalesced messages. */
internal sealed interface Receiving {
    data object PerDatagram : Receiving

    /** `UDP_GRO` is on: each read is one `recvmmsg`, split into datagrams per message. */
    data object Coalesced : Receiving
}

/**
 * A UDP socket `qnet` opened: non-blocking, close-on-exec, its buffers asked of the kernel and read back, bound and, for a
 * client, connected. [spec] is the native block the socket was opened from; [reopen] opens it again from the same
 * block on the same local address and port. Its batching is probed once, at open ([SocketOpening]), and [gro] says
 * how `UDP_GRO` went the last time a descriptor was opened.
 */
internal class NativeSocket internal constructor(
    private val shim: NetShim,
    private val spec: NativeBytes,
    first: Int,
    private val options: AppliedOptions,
    private val wantsGro: Boolean,
    val capability: BatchCapability,
    val receiving: Receiving,
) {
    val cell = DescriptorCell(first)

    /** What the socket's last open did about `UDP_GRO`. */
    @Volatile
    var gro: GroSetup = options.gro
        private set

    /** Whether this socket's datagrams carry DF, as it was set at its last open. */
    @Volatile
    var dontFragment: DontFragment = options.dontFragment
        private set

    /** 4 or 6: the family of the socket, which an IPv4 target on an IPv6 socket is mapped into. */
    val family: Int = spec.intAt(SpecLayout.BIND)

    val local: PathKey get() = recordAt(spec, SpecLayout.LOCAL)

    val grantedReceive: Int get() = spec.intAt(SpecLayout.GRANTED_RCV)

    val grantedSend: Int get() = spec.intAt(SpecLayout.GRANTED_SND)

    /**
     * Loop thread: closes this socket and opens one on the address and port it held, to the same peer. A socket that
     * cannot be opened there again leaves the cell closed, so no call reaches a descriptor number reused since.
     */
    fun reopen(): Reopened {
        copyRecord(spec, SpecLayout.LOCAL, SpecLayout.BIND)
        var answer: Reopened = Reopened.SameEndpoint
        cell.replace { old ->
            shim.close(old)
            val fd = shim.udpOpenSocket(spec.address)
            if (fd >= 0) {
                record(applyOptions(shim, fd, spec.intAt(SpecLayout.BIND), wantsGro))
                Swapped.To(fd)
            } else {
                answer = Reopened.Refused(Thrown(NativeSocketRefusal(SocketRefusal.of(-fd))))
                Swapped.Gone
            }
        }
        return answer
    }

    /**
     * Loop thread: opens a socket on [bind]'s address and a port of its own, to the same peer, and closes this one once
     * the new one is open. A socket that cannot be opened there leaves this one in place.
     */
    fun moveTo(bind: PathKey): Moved {
        putRecord(spec, SpecLayout.BIND, PathKey(bind.family, 0, bind.hi, bind.lo))
        var answer: Moved = Moved.Unsupported
        cell.replace { old ->
            val fd = shim.udpOpenSocket(spec.address)
            if (fd >= 0) {
                record(applyOptions(shim, fd, spec.intAt(SpecLayout.BIND), wantsGro))
                shim.close(old)
                answer = Moved.To(recordAt(spec, SpecLayout.LOCAL))
                Swapped.To(fd)
            } else {
                answer = Moved.Refused(Thrown(NativeSocketRefusal(SocketRefusal.of(-fd))))
                Swapped.To(old)
            }
        }
        return answer
    }

    private fun record(applied: AppliedOptions) {
        gro = applied.gro
        dontFragment = applied.dontFragment
    }

    companion object {
        /** The spec block's size in bytes, as `qnet.h` lays it out. */
        const val SPEC_BYTES = SpecLayout.BYTES

        /** The most datagrams one coalesced read holds: a socket asks for `UDP_GRO` only when its batch can take them all. */
        const val GRO_MAX_DATAGRAMS = 64

        fun open(
            shim: NetShim,
            spec: NativeBytes,
            bind: PathKey,
            peer: SocketPeer,
            receiveBytes: Int,
            sendBytes: Int,
            ceiling: UdpBatching,
            maxDatagrams: Int,
            gro: GroRequest = GroRequest.Declined,
        ): NativeOpened = SocketOpening.open(shim, spec, bind, peer, receiveBytes, sendBytes, ceiling, maxDatagrams, gro)

        /** The newest batching both the kernel and [batching] allow, chosen once per socket. */
        fun capabilityOf(
            caps: Int,
            segments: Int,
            batching: UdpBatching,
            maxDatagrams: Int,
            gro: Gro = Gro.Off,
        ): BatchCapability = SocketOpening.capabilityOf(caps, segments, batching, maxDatagrams, gro)
    }
}
