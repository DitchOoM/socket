package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel as NioChannel

/**
 * The JVM/Android default: [BufferFactory.Default] hands out a NIO-writable direct buffer (a
 * [com.ditchoom.buffer.BaseJvmBuffer] whose `byteBuffer` the channel receives into, and whose
 * `nativeAddress` downstream FFI reads) — the exact strategy [NioDatagramChannelCore] has always used.
 */
internal actual val defaultDatagramBufferFactory: BufferFactory = BufferFactory.Default

/**
 * JVM/Android [UdpSocket] over NIO [NioChannel]. Shared by both platforms via `commonJvmMain` (NIO is
 * identical on the JVM and on Android's ART).
 */
@ExperimentalDatagramApi
actual object UdpSocket {
    init {
        // Installing the platform resolver here means merely referencing UdpSocket wires
        // SocketAddress.resolve() to real DNS for the whole process (resolved-only model, RFC §10.1).
        SocketAddress.installResolver(JvmSocketAddressResolver)
    }

    actual suspend fun bind(
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): AddressedDatagramChannel =
        NioChannel.open().closedIfSetupFails {
            configureBlocking(false)
            bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
            // An addressed channel's localAddress is non-null by construction (buffer-flow contract):
            // if getsockname cannot report the bound address, fail fast HERE, before any channel exists.
            val local =
                (localAddress as? InetSocketAddress)?.let { InternedJvmSocketAddress(it) }
                    ?: error("bound UDP socket reports no local address (getsockname)")
            AddressedNioDatagramChannel(this, local, receiveBufferSize, bufferFactory)
        }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): ConnectedDatagramChannel {
        // Resolve the peer out of band (numeric literal → no DNS), then pin it as the channel's fixed
        // peer. A `connect()`ed UDP socket only receives from — and `write()`s to — this address.
        val peer = resolve(remoteHost, remotePort)
        return NioChannel.open().closedIfSetupFails {
            configureBlocking(false)
            bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
            connect(peer.toInetSocketAddress())
            // Connected mode reports the typed maybe-known LocalAddress (no fail-fast contract here).
            val local =
                (localAddress as? InetSocketAddress)
                    ?.let { LocalAddress.of(InternedJvmSocketAddress(it)) } ?: LocalAddress.Unknown
            ConnectedNioDatagramChannel(this, peer, local, receiveBufferSize, bufferFactory)
        }
    }

    actual suspend fun bindMulticast(
        port: Int,
        family: AddressFamily,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): MulticastDatagramChannel {
        // Multicast needs a protocol-family-typed channel: DatagramChannel.join() throws on a channel
        // opened with the no-arg open(). SO_REUSEADDR before bind lets multiple listeners share the port.
        val v6 = family == AddressFamily.IPv6
        val channel = NioChannel.open(if (v6) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        return channel.closedIfSetupFails {
            setOption(StandardSocketOptions.SO_REUSEADDR, true)
            configureBlocking(false)
            bind(InetSocketAddress(if (v6) WILDCARD_V6 else WILDCARD, port))
            // Same fail-fast-before-construction contract as bind(): multicast is an addressed channel.
            val local =
                (localAddress as? InetSocketAddress)?.let { InternedJvmSocketAddress(it) }
                    ?: error("bound UDP socket reports no local address (getsockname)")
            val base = AddressedNioDatagramChannel(this, local, receiveBufferSize, bufferFactory)
            MulticastNioDatagramChannel(this, base)
        }
    }

    actual suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress = SocketAddress.resolve(host, port)

    /**
     * Turns a freshly opened channel into the [setup] result, and closes the channel if anything in
     * between is refused — so a refusal propagates with nothing left behind.
     *
     * A UDP socket exists from `open()`, before it is bindable, connectable or wrappable, and every
     * step after that can be refused: the bind (an explicitly requested endpoint another socket holds),
     * the connect (a 4-tuple another socket holds — the #434 collision), `getsockname` reporting no
     * address. Without this guard the channel was abandoned where it stood, and with it the ephemeral
     * port a successful bind had already reserved: the descriptor stayed allocated until a GC `Cleaner`
     * happened to run, which no failure path guarantees. Measured on macOS/JDK 21, 64 refusals of each
     * kind:
     *
     * | refused at | leaked descriptors, unguarded | with this guard |
     * |---|---|---|
     * | `Net.bind0` | 64 of 64 | 0 |
     * | `Net.connect0` | 64 of 64 | 0 |
     *
     * The guard wraps construction too, so the addressed channels' fail-fast on an unreportable
     * `getsockname` needs no close of its own: it throws inside [setup] like any refused syscall.
     *
     * Two of the other actuals already did this — the Linux one `close(fd)`s on either failure, the
     * Apple one cancels the `NWConnection`. The Node one does not, and leaks the same way (#521).
     */
    private inline fun <T> NioChannel.closedIfSetupFails(setup: NioChannel.() -> T): T {
        try {
            return setup()
        } catch (t: Throwable) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                t.addSuppressed(closeFailure)
            }
            throw t
        }
    }

    private const val WILDCARD = "0.0.0.0"
    private const val WILDCARD_V6 = "::"
}
