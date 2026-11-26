@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How a socket's reads reach the loop: one datagram per message, or split from coalesced messages. */
internal sealed interface Receiving {
    data object PerDatagram : Receiving

    /** `UDP_GRO` is on: each read is one `recvmmsg`, split into datagrams per message. */
    data object Coalesced : Receiving
}

/** A client's sockets as senders read them: the carrying one, and the second beside it while a move is open. */
internal sealed interface NativePair {
    /** [family] is 4 or 6: the carrying socket's, which an IPv4 target on an IPv6 socket is mapped into. */
    class One(
        val carrying: SocketTicket,
        val family: Int,
    ) : NativePair

    class Two(
        val carrying: SocketTicket,
        val family: Int,
        val second: SecondDescriptor,
    ) : NativePair
}

/** The carrying socket's ticket. */
internal fun NativePair.carrying(): SocketTicket =
    when (this) {
        is NativePair.One -> carrying
        is NativePair.Two -> carrying
    }

/** The carrying socket's family. */
internal fun NativePair.family(): Int =
    when (this) {
        is NativePair.One -> family
        is NativePair.Two -> family
    }

/** A descriptor opened beside the carrying one: its ticket, cell, local address, family and options. */
internal class SecondDescriptor(
    val ticket: SocketTicket,
    val cell: DescriptorCell,
    val local: PathKey,
    val family: Int,
    val options: AppliedOptions,
) : ReceiveOn

/**
 * A UDP socket `qnet` opened: non-blocking, close-on-exec, its buffers asked of the kernel and read back, bound and, for a
 * client, connected. [spec] is the native block the socket was opened from; [reopen] opens it again from the same
 * block on the same local address and port. A client's move opens a second descriptor beside it ([openBeside]), which
 * [promote] makes the carrying one or [closeSecond] closes: never more than two. Its batching is probed once, at open ([SocketOpening]), and [gro] says
 * how `UDP_GRO` went the last time a descriptor was opened.
 */
@Published
internal class NativeSocket internal constructor(
    private val shim: NetShim,
    private val spec: NativeBytes,
    first: Int,
    firstOptions: AppliedOptions,
    private val request: GroRequest,
    val capability: BatchCapability,
    val receiving: Receiving,
) {
    val cell = DescriptorCell(first)

    private val options = OptionsCell(firstOptions)

    /** What the socket's last open did about `UDP_GRO`. */
    val gro: GroSetup get() = options.load().gro

    /** Whether this socket's datagrams carry DF, as it was set at its last open. */
    val dontFragment: DontFragment get() = options.load().dontFragment

    private val pair = AtomicReference<NativePair>(NativePair.One(SocketTicket.FIRST, spec.intAt(SpecLayout.BIND)))

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
        val replaced =
            cell.replace { old ->
                shim.close(old)
                val fd = shim.udpOpenSocket(spec.address)
                if (fd >= 0) {
                    options.store(applyOptions(shim, fd, spec.intAt(SpecLayout.BIND), request))
                    Swapped.To(fd)
                } else {
                    answer = Reopened.Refused(Thrown(NativeSocketRefusal(SocketRefusal.of(-fd))))
                    Swapped.Gone
                }
            }
        return when (replaced) {
            Quiesced.Quiet -> answer
            // Closed already: nothing was closed or opened, and the number it held is not this socket's any more.
            Quiesced.WasClosed -> Reopened.Refused(Thrown(NativeSocketRefusal(SocketRefusal.EBadF)))
        }
    }

    /**
     * Loop thread, with no second socket open: opens a socket on [bind]'s address and a port of its own, to the same
     * peer, beside this one, and names it [ticket]. The carrying descriptor and the local address this socket reports
     * are unchanged until [promote].
     */
    fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside {
        val carrying = pair.load()
        val carryingLocal = local
        putRecord(spec, SpecLayout.BIND, PathKey(bind.family, 0, bind.hi, bind.lo))
        val fd = shim.udpOpenSocket(spec.address)
        if (fd < 0) {
            putRecord(spec, SpecLayout.LOCAL, carryingLocal)
            return Beside.Refused(Thrown(NativeSocketRefusal(SocketRefusal.of(-fd))))
        }
        val family = spec.intAt(SpecLayout.BIND)
        val applied = applyOptions(shim, fd, family, request)
        val second = SecondDescriptor(ticket, DescriptorCell(fd), recordAt(spec, SpecLayout.LOCAL), family, applied)
        putRecord(spec, SpecLayout.LOCAL, carryingLocal)
        pair.store(NativePair.Two(carrying.carrying(), carrying.family(), second))
        return Beside.Opened(second.local)
    }

    /**
     * Loop thread: the second descriptor carries from now on and the old one is closed. Senders that met the second's own
     * cell closed drop what they held; from the swap on, its ticket names the carrying cell.
     */
    fun promote() {
        val two =
            when (val now = pair.load()) {
                is NativePair.Two -> now
                is NativePair.One -> return
            }
        val second = two.second
        // Quiesced, not closed: the descriptor lives on in the carrying cell.
        second.cell.close { }
        val swapped =
            cell.replace { old ->
                shim.close(old)
                Swapped.To(second.cell.descriptor())
            }
        when (swapped) {
            Quiesced.Quiet -> Unit
            // The socket was closed already: the second descriptor has no cell to carry it, and is closed too.
            Quiesced.WasClosed -> shim.close(second.cell.descriptor())
        }
        putRecord(spec, SpecLayout.LOCAL, second.local)
        options.store(second.options)
        pair.store(NativePair.One(second.ticket, second.family))
    }

    /** Loop thread: [reopen] for the second descriptor, on its own address and port; one that cannot be is closed. */
    fun reopenSecond(): Reopened {
        val two =
            when (val now = pair.load()) {
                is NativePair.Two -> now
                is NativePair.One -> return Reopened.SameEndpoint
            }
        val old = two.second
        val carryingLocal = local
        old.cell.close { fd -> shim.close(fd) }
        putRecord(spec, SpecLayout.BIND, old.local)
        val fd = shim.udpOpenSocket(spec.address)
        putRecord(spec, SpecLayout.LOCAL, carryingLocal)
        if (fd < 0) {
            pair.store(NativePair.One(two.carrying, two.family))
            return Reopened.Refused(Thrown(NativeSocketRefusal(SocketRefusal.of(-fd))))
        }
        val applied = applyOptions(shim, fd, old.family, request)
        pair.store(
            NativePair.Two(two.carrying, two.family, SecondDescriptor(old.ticket, DescriptorCell(fd), old.local, old.family, applied)),
        )
        return Reopened.SameEndpoint
    }

    /** Loop thread: closes the second descriptor, once no sender is inside a call on it. */
    fun closeSecond() {
        when (val now = pair.load()) {
            is NativePair.Two -> {
                pair.store(NativePair.One(now.carrying, now.family))
                now.second.cell.close { fd -> shim.close(fd) }
            }
            is NativePair.One -> Unit
        }
    }

    /** Any thread: the carrying socket's ticket and family, and the second descriptor while one is open. */
    fun pair(): NativePair = pair.load()

    /** The second descriptor, for the poller, once one is open; the carrying one otherwise. */
    fun secondDescriptor(): Int =
        when (val now = pair.load()) {
            is NativePair.Two -> now.second.cell.descriptor()
            is NativePair.One -> cell.descriptor()
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
