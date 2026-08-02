package com.ditchoom.socket.udp

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.EcnPreference
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetSocketAddress
import java.net.SocketOption
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.DatagramChannel as NioChannel

/** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
private const val MAX_UDP_PAYLOAD = 65507

/**
 * JVM/Android [DatagramChannel] machinery backed by a NIO [NioChannel] + [Selector] — the real-socket
 * lift of the quiche `NioUdpChannel`, cleaned to the public datagram shape (RFC §7):
 *
 * - **per-packet source exposed** — `NioUdpChannel.receive` returned only a length and threw the sender
 *   away; here [receive] returns the source via [NioChannel.receive] and surfaces it as [Datagram.peer].
 * - **1-entry `lastDest` cache dropped** — an addressed send extracts the destination's owned
 *   [InetSocketAddress] with a field read ([SocketAddress.toInetSocketAddress]); no reconstruction to
 *   amortize (RFC §4).
 * - **control plane wired to the platform ceiling** — ECN/DSCP via `IP_TOS` and DF via `IP_DONTFRAGMENT`
 *   where [NioChannel.supportedOptions] offers them; capabilities are computed from that set (§7.1),
 *   never assumed. NIO cannot read receive-side ancillary data, so all read-side control fields degrade
 *   to their §7.2 typed absent states.
 *
 * The addressing mode is fixed at construction, in the type: [ConnectedNioDatagramChannel] (fixed peer,
 * no destination parameter) vs [AddressedNioDatagramChannel] (every send names its destination). This
 * core holds only the mode-agnostic machinery — receive loop, control-plane appliers, lifecycle — and
 * deliberately implements neither send refinement.
 *
 * Cancellation-correct: the channel is non-blocking and only [Selector.select] blocks, inside
 * [runInterruptible]. Cancelling a parked [receive] interrupts the select — it does NOT close the socket
 * (a blocking `receive()` would `ClosedByInterruptException` it). Not thread-safe; confine [receive] and
 * `send` each to one coroutine, per the buffer-flow contract.
 */
@ExperimentalDatagramApi
internal abstract class NioDatagramChannelCore(
    protected val channel: NioChannel,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.Default,
) : DatagramChannel {
    private val selector: Selector = Selector.open().also { channel.register(it, SelectionKey.OP_READ) }

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && channel.isOpen

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). Path-MTU/PMTUD is a consumer concern. */
    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Control-plane options resolved reflectively from what THIS socket actually supports (the same
    // DatagramChannel.supportedOptions() probe used to seed the §7.1 matrix). Matching by name keeps
    // commonJvmMain free of a hard compile-time dependency on JDK-19+ / Android-version-specific option
    // constants, so the shared source set compiles and degrades correctly on every JVM and Android level.
    private val supportedOptions: Set<SocketOption<*>> = channel.supportedOptions()

    private fun optionNamed(name: String): SocketOption<*>? = supportedOptions.firstOrNull { it.name() == name }

    private val ipTosOption: SocketOption<*>? = optionNamed("IP_TOS")
    private val dontFragmentOption: SocketOption<*>? = optionNamed("IP_DONTFRAGMENT")

    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(
            ecnSend = ipTosOption != null,
            ecnReceive = false, // NIO exposes no receive-side ancillary data (no recv cmsg)
            dscpSend = ipTosOption != null,
            dontFragment = dontFragmentOption != null,
            hopLimitSend = false, // NIO has no unicast TTL option (only IP_MULTICAST_TTL)
            hopLimitReceive = false,
            localAddressReceive = false, // no IP_PKTINFO on NIO
            sourceAddressSelect = false,
            multicast = false, // design-for, defer to Phase 5
        )

    // IP_TOS / IP_DONTFRAGMENT are socket-wide on NIO (there is no per-datagram ancillary send path), so
    // apply only on change to avoid a redundant setsockopt on every send.
    private var appliedTos = Int.MIN_VALUE
    private var appliedDontFragment = false

    private fun applyControlPlane(options: DatagramSendOptions) {
        val tosOpt = ipTosOption
        if (tosOpt != null && (options.ecn != EcnPreference.OsDefault || options.dscp >= 0)) {
            val dscpBits = if (options.dscp >= 0) options.dscp else 0
            val ecnBits = if (options.ecn != EcnPreference.OsDefault) options.ecn.codepoint else 0
            val tos = (dscpBits shl 2) or ecnBits
            if (tos != appliedTos) {
                @Suppress("UNCHECKED_CAST")
                runCatching { channel.setOption(tosOpt as SocketOption<Int>, tos) }.onSuccess { appliedTos = tos }
            }
        }
        val dfOpt = dontFragmentOption
        if (dfOpt != null && options.dontFragment != appliedDontFragment) {
            @Suppress("UNCHECKED_CAST")
            runCatching { channel.setOption(dfOpt as SocketOption<Boolean>, options.dontFragment) }
                .onSuccess { appliedDontFragment = options.dontFragment }
        }
    }

    override suspend fun receive(): DatagramReadResult {
        while (true) {
            if (closed) return DatagramReadResult.Closed()
            // select() is the only blocking call; runInterruptible makes a cancelled receive interrupt
            // the select (which returns) without closing the underlying socket.
            runInterruptible(Dispatchers.IO) { selector.select() }
            selector.selectedKeys().clear()
            if (closed) return DatagramReadResult.Closed()

            val payload = bufferFactory.allocate(receiveBufferSize)
            val byteBuffer = (payload.unwrapFully() as BaseJvmBuffer).byteBuffer
            byteBuffer.clear()
            // Both modes receive via channel.receive(): on a connected socket the reported sender IS the
            // connected peer (value-equal via InternedJvmSocketAddress).
            val sender = channel.receive(byteBuffer) as InetSocketAddress? ?: continue // spurious wakeup
            val length = byteBuffer.position()
            // Expose exactly the received datagram as the readable window [0, length).
            payload.position(0)
            payload.setLimit(length)
            return DatagramReadResult.Received(
                Datagram(
                    payload = payload,
                    peer = InternedJvmSocketAddress(sender),
                    ecn = Ecn.Unknown,
                    localAddress = LocalAddress.Unknown,
                    hopLimit = HopLimit.Unknown,
                ),
            )
        }
    }

    /** Shared send staging: closed-sink guard, control plane, and the zero-copy view to transmit. */
    protected fun stage(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ): ByteBuffer {
        check(!closed) { "sink is closed" }
        applyControlPlane(options)
        // slice(): independent view over the remaining bytes — send-does-not-consume, no copy.
        return (payload.slice().unwrapFully() as BaseJvmBuffer).byteBuffer
    }

    override fun close() {
        closed = true
        runCatching {
            selector.wakeup()
            selector.close()
        }
        runCatching { channel.close() }
    }
}

/**
 * The **connected** (single fixed [peer]) mode of the NIO channel — what [UdpSocket.connect] returns.
 * `send` takes no destination; the kernel routes every datagram to the peer fixed at `connect()` time.
 */
@ExperimentalDatagramApi
internal class ConnectedNioDatagramChannel(
    channel: NioChannel,
    override val peer: SocketAddress,
    override val localAddress: LocalAddress,
    receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    bufferFactory: BufferFactory = BufferFactory.Default,
) : NioDatagramChannelCore(channel, receiveBufferSize, bufferFactory),
    ConnectedDatagramChannel {
    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        channel.write(stage(payload, options)) // connected: kernel routes to the fixed peer
    }
}

/**
 * The **addressed** (many peers) mode of the NIO channel — what [UdpSocket.bind] returns. Every send
 * names its destination; [localAddress] is plainly non-null (bind fails fast on getsockname failure).
 */
@ExperimentalDatagramApi
internal class AddressedNioDatagramChannel(
    channel: NioChannel,
    override val localAddress: SocketAddress,
    receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    bufferFactory: BufferFactory = BufferFactory.Default,
) : NioDatagramChannelCore(channel, receiveBufferSize, bufferFactory),
    AddressedDatagramChannel {
    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        channel.send(stage(payload, options), to.toInetSocketAddress())
    }
}
