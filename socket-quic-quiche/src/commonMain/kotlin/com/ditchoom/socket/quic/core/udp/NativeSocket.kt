package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.UdpBatching

/** Where a native socket sends: one peer it is connected to, or any address a datagram names. */
internal sealed interface SocketPeer {
    class Connected(
        val peer: PathKey,
    ) : SocketPeer

    data object Unconnected : SocketPeer
}

/** What [NativeSocket.open] did. */
internal sealed interface NativeOpened {
    class Opened(
        val socket: NativeSocket,
    ) : NativeOpened

    class Refused(
        val refusal: SocketRefusal,
    ) : NativeOpened
}

/** Whether a socket may ask for `UDP_GRO`, and where the memory its coalesced reads land in comes from. */
internal sealed interface GroMemory {
    /** The socket does not ask for `UDP_GRO`. */
    data object None : GroMemory

    /** The socket asks for it, and [allocate] gives the read memory of one socket that got it. */
    class Allocating(
        val allocate: (bytes: Int) -> NativeBytes,
    ) : GroMemory
}

/** What a socket's `UDP_GRO` came to, decided at open and again at every reopen. */
internal sealed interface GroSetup {
    /** Not asked for: the socket's batching ceiling does not use it. */
    data object NotRequested : GroSetup

    /** Asked for, and this platform offers none. */
    data object Unavailable : GroSetup

    /** Asked for, and the kernel refused it: the socket receives one datagram per message. */
    class Refused(
        val refusal: SocketRefusal,
    ) : GroSetup

    data object Enabled : GroSetup
}

/** How a socket's reads reach the loop: one datagram per message, or split from coalesced messages. */
internal sealed interface Receiving {
    data object PerDatagram : Receiving

    /** `UDP_GRO` is on: each read lands in [scratch] and is split into datagrams. */
    class Coalesced(
        val scratch: NativeBytes,
    ) : Receiving
}

/**
 * A UDP socket `qnet` opened: non-blocking, close-on-exec, its buffers asked for and read back, bound and, for a
 * client, connected. [spec] is the native block the socket was opened from; [reopen] opens it again from the same
 * block on the same local address and port. Its batching is probed once, here, and [gro] says how `UDP_GRO` went.
 */
internal class NativeSocket private constructor(
    private val shim: NetShim,
    private val spec: NativeBytes,
    first: Int,
    val capability: BatchCapability,
    val receiving: Receiving,
) {
    val cell = DescriptorCell(first)

    /** What the socket's last open did about `UDP_GRO`. */
    val gro: GroSetup get() = groSetupOf(spec.intAt(GRO_WANT), spec.intAt(GRO_STATE))

    /** 4 or 6: the family of the socket, which an IPv4 target on an IPv6 socket is mapped into. */
    val family: Int = spec.intAt(BIND)

    val local: PathKey get() = recordAt(spec, LOCAL)

    val grantedReceive: Int get() = spec.intAt(GRANTED_RCV)

    val grantedSend: Int get() = spec.intAt(GRANTED_SND)

    /**
     * Loop thread: closes this socket and opens one on the address and port it held, to the same peer. A socket that
     * cannot be opened there again leaves the cell closed, so no call reaches a descriptor number reused since.
     */
    fun reopen(): Reopened {
        copyRecord(spec, LOCAL, BIND)
        var answer: Reopened = Reopened.SameEndpoint
        cell.replace { old ->
            shim.close(old)
            val fd = shim.udpOpen(spec.address)
            if (fd >= 0) {
                Swapped.To(fd)
            } else {
                answer = Reopened.Refused(Thrown(NativeSocketRefusal(SocketRefusal.of(-fd))))
                Swapped.Gone
            }
        }
        return answer
    }

    companion object {
        /** The spec block's size in bytes, as `qnet.h` lays it out. */
        const val SPEC_BYTES = 104

        /** The bytes one coalesced read lands in (`QN_GRO_SCRATCH_BYTES`): a `UDP_GRO` message is at most 64 KiB. */
        const val GRO_SCRATCH_BYTES = 65600

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
            gro: GroMemory = GroMemory.None,
        ): NativeOpened {
            val wantsGro = gro is GroMemory.Allocating && ceiling.usesSegments() && maxDatagrams >= GRO_MAX_DATAGRAMS
            spec.putInt(GRO_WANT, if (wantsGro) 1 else 0)
            putRecord(spec, BIND, bind)
            when (peer) {
                is SocketPeer.Connected -> putRecord(spec, PEER, peer.peer)
                is SocketPeer.Unconnected -> spec.putInt(PEER, 0)
            }
            spec.putInt(RCVBUF, receiveBytes)
            spec.putInt(SNDBUF, sendBytes)
            val fd = shim.udpOpen(spec.address)
            if (fd < 0) return NativeOpened.Refused(SocketRefusal.of(-fd))
            val receiving =
                when (groSetupOf(spec.intAt(GRO_WANT), spec.intAt(GRO_STATE))) {
                    is GroSetup.Enabled ->
                        when (gro) {
                            is GroMemory.Allocating -> Receiving.Coalesced(gro.allocate(GRO_SCRATCH_BYTES))
                            is GroMemory.None -> Receiving.PerDatagram
                        }
                    is GroSetup.NotRequested, is GroSetup.Unavailable, is GroSetup.Refused -> Receiving.PerDatagram
                }
            val coalescing =
                when (receiving) {
                    is Receiving.Coalesced -> Gro.On
                    is Receiving.PerDatagram -> Gro.Off
                }
            val capability = capabilityOf(spec.intAt(CAPS), spec.intAt(GSO_SEGMENTS), ceiling, maxDatagrams, coalescing)
            return NativeOpened.Opened(NativeSocket(shim, spec, fd, capability, receiving))
        }

        /** The newest batching both the kernel and [batching] allow, chosen once per socket. */
        fun capabilityOf(
            caps: Int,
            segments: Int,
            batching: UdpBatching,
            maxDatagrams: Int,
            gro: Gro = Gro.Off,
        ): BatchCapability {
            val multi = caps and CAP_MULTI != 0
            val publicMulti = multi && caps and CAP_PRIVATE == 0
            val gso = caps and CAP_GSO != 0
            return when (batching) {
                UdpBatching.Newest -> newest(gso, gro, multi, segments, maxDatagrams)
                UdpBatching.PublicCallsOnly -> newest(gso, gro, publicMulti, segments, maxDatagrams)
                UdpBatching.WithoutSegments -> if (multi) BatchCapability.Multi(maxDatagrams) else BatchCapability.OneByOne
                UdpBatching.OneDatagramPerCall -> BatchCapability.OneByOne
            }
        }

        private fun newest(
            gso: Boolean,
            gro: Gro,
            multi: Boolean,
            segments: Int,
            maxDatagrams: Int,
        ): BatchCapability =
            when {
                gso -> BatchCapability.Segmented(maxDatagrams, Gso.On(segments), gro)
                gro == Gro.On -> BatchCapability.Segmented(maxDatagrams, Gso.Off, gro)
                multi -> BatchCapability.Multi(maxDatagrams)
                else -> BatchCapability.OneByOne
            }

        /** The `UDP_GRO` outcome `qnet` wrote: [wanted] is what was asked, [state] what came of it. */
        private fun groSetupOf(
            wanted: Int,
            state: Int,
        ): GroSetup =
            when {
                wanted == 0 -> GroSetup.NotRequested
                state > 0 -> GroSetup.Enabled
                state == 0 -> GroSetup.Unavailable
                else -> GroSetup.Refused(SocketRefusal.of(-state))
            }

        /** Only the batchings that segment ask for `UDP_GRO`: it is the receive half of `UDP_SEGMENT`. */
        private fun UdpBatching.usesSegments(): Boolean =
            when (this) {
                UdpBatching.Newest, UdpBatching.PublicCallsOnly -> true
                UdpBatching.WithoutSegments, UdpBatching.OneDatagramPerCall -> false
            }

        private const val BIND = 0
        private const val PEER = 24
        private const val RCVBUF = 48
        private const val SNDBUF = 52
        private const val GRANTED_RCV = 56
        private const val GRANTED_SND = 60
        private const val LOCAL = 64
        private const val CAPS = 88
        private const val GSO_SEGMENTS = 92
        private const val GRO_WANT = 96
        private const val GRO_STATE = 100
        private const val CAP_MULTI = 1
        private const val CAP_GSO = 2
        private const val CAP_PRIVATE = 8
    }
}

/** Writes [key] as a `qnet` address record (family, port, hi, lo) at [offset]. */
internal fun putRecord(
    bytes: NativeBytes,
    offset: Int,
    key: PathKey,
) {
    bytes.putInt(offset, key.family)
    bytes.putInt(offset + 4, key.port)
    bytes.putLong(offset + 8, key.hi)
    bytes.putLong(offset + 16, key.lo)
}

/** Reads the `qnet` address record at [offset]. */
internal fun recordAt(
    bytes: NativeBytes,
    offset: Int,
): PathKey = PathKey(bytes.intAt(offset), bytes.intAt(offset + 4), bytes.longAt(offset + 8), bytes.longAt(offset + 16))

private fun copyRecord(
    bytes: NativeBytes,
    from: Int,
    to: Int,
) = bytes.copyFrom(bytes, from, to, RECORD_BYTES)

private const val RECORD_BYTES = 24
