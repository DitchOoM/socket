package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.UdpBatching

/** Opening a [NativeSocket]: the spec block, the options, the batching probe. */
internal object SocketOpening {
    /** Opens a socket from the spec block, or says the loaded `qnet` lays it out differently ([NativeOpened.LayoutMismatch]). */
    fun open(
        shim: NetShim,
        spec: NativeBytes,
        bind: PathKey,
        peer: SocketPeer,
        receiveBytes: Int,
        sendBytes: Int,
        ceiling: UdpBatching,
        maxDatagrams: Int,
        gro: GroRequest,
    ): NativeOpened {
        val reported = shim.specLayout()
        if (reported != SpecLayout.FINGERPRINT) {
            return NativeOpened.LayoutMismatch(SpecLayoutMismatch(SpecLayout.FINGERPRINT, reported))
        }
        val request =
            if (gro is GroRequest.Requested && GroRequest.of(ceiling) is GroRequest.Requested && maxDatagrams >= NativeSocket.GRO_MAX_DATAGRAMS) {
                GroRequest.Requested
            } else {
                GroRequest.Declined
            }
        putRecord(spec, SpecLayout.BIND, bind)
        when (peer) {
            is SocketPeer.Connected -> putRecord(spec, SpecLayout.PEER, peer.peer)
            is SocketPeer.Unconnected -> spec.putInt(SpecLayout.PEER, 0)
        }
        spec.putInt(SpecLayout.RCVBUF, receiveBytes)
        spec.putInt(SpecLayout.SNDBUF, sendBytes)
        val fd = shim.udpOpenSocket(spec.address)
        if (fd < 0) return NativeOpened.Refused(SocketRefusal.of(-fd))
        val applied = applyOptions(shim, fd, spec.intAt(SpecLayout.BIND), request)
        val receiving =
            when (applied.gro) {
                is GroSetup.Enabled -> Receiving.Coalesced
                is GroSetup.NotRequested, is GroSetup.Unavailable, is GroSetup.Refused -> Receiving.PerDatagram
            }
        val coalescing =
            when (receiving) {
                is Receiving.Coalesced -> Gro.On
                is Receiving.PerDatagram -> Gro.Off
            }
        val capabilities = shim.udpCapabilities(fd)
        val capability = capabilityOf(capabilities and CAP_MASK, capabilities ushr SEGMENTS_SHIFT, ceiling, maxDatagrams, coalescing)
        return NativeOpened.Opened(NativeSocket(shim, spec, fd, applied, request, capability, receiving))
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

    private const val CAP_MULTI = 1
    private const val CAP_GSO = 2
    private const val CAP_PRIVATE = 8
    private const val CAP_MASK = 0xFF
    private const val SEGMENTS_SHIFT = 8
}
