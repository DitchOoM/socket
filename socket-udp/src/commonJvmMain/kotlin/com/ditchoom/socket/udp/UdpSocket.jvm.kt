package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import java.io.IOException
import java.net.BindException
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
    ): AddressedDatagramChannel {
        if (localHost != null) return boundTo(localHost, localPort, receiveBufferSize, bufferFactory)
        // Wildcard: take the port from the IPv4 table first — see [wildcardPortOwnedForIpv4]. Only the
        // ephemeral case retries, and only because the port IPv4 offered can, rarely, be held by an
        // IPv6-only socket, which nothing but the real bind discovers.
        var lastFailure: BindException? = null
        repeat(if (localPort == 0) MAX_WILDCARD_BIND_ATTEMPTS else 1) {
            val port = wildcardPortOwnedForIpv4(localPort)
            try {
                return boundTo(WILDCARD, port, receiveBufferSize, bufferFactory)
            } catch (e: BindException) {
                lastFailure = e
            }
        }
        throw lastFailure ?: BindException("Failed to bind a wildcard UDP port")
    }

    private fun boundTo(
        host: String,
        port: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): AddressedDatagramChannel =
        NioChannel.open().closedIfSetupFails {
            configureBlocking(false)
            bind(InetSocketAddress(host, port))
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
        // A refusal anywhere on the way — open, bind, connect — reports typed (#534): the JDK's own
        // BindException / NoRouteToHostException / SocketException phrase is classified onto
        // UdpConnectError and kept as the cause, so a caller can branch on the reason on every backend.
        return try {
            NioChannel.open().closedIfSetupFails {
                configureBlocking(false)
                bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
                connect(peer.toInetSocketAddress())
                // Connected mode reports the typed maybe-known LocalAddress (no fail-fast contract here).
                val local =
                    (localAddress as? InetSocketAddress)
                        ?.let { LocalAddress.of(InternedJvmSocketAddress(it)) } ?: LocalAddress.Unknown
                ConnectedNioDatagramChannel(this, peer, local, receiveBufferSize, bufferFactory)
            }
        } catch (e: IOException) {
            throw UdpConnectException(jvmConnectErrorOf(e), e)
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

    /**
     * The port a wildcard bind should use, chosen (or validated) through an **IPv4-only** socket first.
     *
     * `DatagramChannel.open()` takes no protocol family, so on a dual-stack host the JDK returns an
     * `AF_INET6` socket with `IPV6_V6ONLY` cleared and turns the `0.0.0.0` wildcard into `in6addr_any`
     * — one socket meant to serve both families (`udp46`). On BSD/Darwin it does not own the IPv4 half
     * of its port: a plain `AF_INET` socket may already hold `0.0.0.0:port`, the dual-stack bind
     * **still succeeds**, and every datagram addressed to `127.0.0.1:port` is delivered to the more
     * specific IPv4 socket. Measured directly on macOS 15 — `socket(AF_INET)` bound to `0.0.0.0:P`,
     * then `socket(AF_INET6, V6ONLY=0)` bound to `[::]:P`: the second bind succeeds and reads nothing,
     * while Linux refuses it outright. The caller is handed a socket that is open, healthy, parked in
     * `select()`, and permanently deaf over IPv4, with no error anywhere.
     *
     * With an ephemeral port that is a lottery nobody can see: `bind(0)` picks from the IPv6 table, so
     * it can hand out a port whose IPv4 half belongs to an unrelated daemon (`homed`, `adb`, …).
     * Measured at roughly 1 bind in 4 000 on a developer Mac, which is what made
     * [#450](https://github.com/DitchOoM/socket/issues/450) look load-dependent: a QUIC server bound
     * that way never receives its client's Initial, the client PTO-retransmits for the whole idle
     * timeout and closes with `local: IdleTimeout`, and the server's trace is empty because no
     * connection was ever created (#367). Only the suite that binds a fresh ephemeral port per test
     * drew often enough to hit it.
     *
     * So the port comes from the IPv4 table before the real socket exists. `bind(0)` on an `AF_INET`
     * probe is handed a port that is free for IPv4 by definition; the probe is closed — UDP has no
     * `TIME_WAIT`, so the port is immediately rebindable — and the dual-stack socket takes it. An
     * explicitly requested port whose IPv4 half is taken fails here with the same [BindException]
     * Linux already raises for the real bind, rather than succeeding into a deaf socket.
     *
     * The probe must come **first**: measured on the same host, an `AF_INET` bind *after* a dual-stack
     * bind on the same port is refused, so probing afterwards would report a conflict with ourselves.
     */
    private fun wildcardPortOwnedForIpv4(requestedPort: Int): Int =
        NioChannel.open(StandardProtocolFamily.INET).closedIfSetupFails {
            bind(InetSocketAddress(WILDCARD, requestedPort))
            val port = (localAddress as InetSocketAddress).port
            close()
            port
        }

    /** Bounded retry for [bind]'s ephemeral wildcard case — see [wildcardPortOwnedForIpv4]. */
    private const val MAX_WILDCARD_BIND_ATTEMPTS = 8

    private const val WILDCARD = "0.0.0.0"
    private const val WILDCARD_V6 = "::"
}
