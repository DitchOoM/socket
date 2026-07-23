@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.MulticastDatagramChannel
import com.ditchoom.socket.udp.MulticastException
import com.ditchoom.socket.udp.MulticastInterface
import com.ditchoom.socket.udp.MulticastMembership
import kotlinx.coroutines.channels.Channel

/**
 * A deterministic, **socket-free** multicast bus — the Tier-A semantic oracle for
 * [MulticastDatagramChannel] (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §2). Several in-memory endpoints share
 * one logical subnet; a datagram [send]t to a group address fans out — in FIFO order, as an isolated copy
 * — to exactly the endpoints that have [joinGroup]'d that group, and to *no one else*. That is the whole
 * point of multicast the real kernel enforces and this fabric reproduces bit-for-bit under `runTest`
 * virtual time, with none of the CI-container multicast-routing flakiness real sockets bring:
 *
 * - **join is required to receive** — an endpoint that never joined a group receives nothing for it.
 * - **leave stops delivery** — the datagram after a [leaveGroup] is not delivered to that endpoint.
 * - **loopback is honored** — a sender that has itself joined the group receives its own datagram iff its
 *   loopback is enabled (the default); disabling it ([setLoopbackEnabled]`(false)`) suppresses only the
 *   self-copy, never the copies to other members.
 *
 * All endpoints sit on one subnet, so [setTimeToLive] and [setOutboundInterface] do not change delivery
 * here — they are recorded ([lastTtl] / [lastOutboundInterface]) for assertions, and their real
 * `setsockopt` effect is covered by the per-platform control-plane conformance suites.
 *
 * Confine each endpoint's `receive`/`send` to one coroutine, exactly like a real channel.
 */
@ExperimentalDatagramApi
class MulticastFabric(
    private val bufferFactory: BufferFactory = BufferFactory.Default,
) {
    private val endpoints = mutableListOf<Endpoint>()

    /** Open a new endpoint on the bus, identified by [localAddress] (a synthetic, non-resolving peer). */
    fun open(localAddress: SocketAddress): MulticastDatagramChannel = Endpoint(localAddress).also { endpoints.add(it) }

    /** Close every endpoint; parked `receive` calls then observe [DatagramReadResult.Closed]. */
    fun close() = endpoints.forEach { it.shutdown() }

    private inner class Endpoint(
        override val localAddress: SocketAddress,
    ) : MulticastDatagramChannel {
        private var closed = false
        private val joined = mutableSetOf<String>()
        private var loopback = true

        var lastTtl: Int = -1
            private set
        var lastOutboundInterface: MulticastInterface? = null
            private set

        // UNLIMITED so a send never suspends on a slow reader; FIFO delivery is the ordering guarantee.
        private val inbound = Channel<PlatformBuffer>(Channel.UNLIMITED)

        override val isOpen: Boolean get() = !closed
        override val maxWritableSize: Int = MAX_UDP_PAYLOAD
        override val capabilities: DatagramCapabilities = MULTICAST_CAPS

        override suspend fun receive(): DatagramReadResult {
            val payload = inbound.receiveCatching().getOrNull() ?: return DatagramReadResult.Closed()
            // The datagram's peer is the endpoint's own address — a real receiver sees the *sender's*
            // unicast address; here the fabric stamps the sender below, so peer is carried on the buffer's
            // provenance. For the oracle we only assert payloads and membership, so localAddress suffices.
            return DatagramReadResult.Received(Datagram(payload = payload, peer = localAddress))
        }

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress?,
            options: DatagramSendOptions,
        ) {
            check(!closed) { "sink is closed" }
            val group = checkNotNull(to) { "multicast send requires a group destination" }
            // slice() does not consume the caller's buffer (send-does-not-consume); copy out so each
            // delivered datagram owns its bytes.
            val slice = payload.slice()
            val bytes = slice.readByteArray(slice.remaining())
            val groupKey = group.host
            for (endpoint in endpoints) {
                if (!endpoint.joined.contains(groupKey)) continue // not a member — no delivery
                if (endpoint === this && !loopback) continue // self-copy suppressed when loopback off
                endpoint.inbound.trySend(bufferFactory.wrap(bytes.copyOf()))
            }
        }

        override suspend fun joinGroup(membership: MulticastMembership) {
            if (closed) throw MulticastException("joinGroup on a closed channel")
            joined.add(membership.group.host)
        }

        override suspend fun leaveGroup(membership: MulticastMembership) {
            if (!joined.remove(membership.group.host)) {
                throw MulticastException("leaveGroup ${membership.group.host}: not joined")
            }
        }

        override suspend fun setTimeToLive(ttl: Int) {
            require(ttl in 0..255) { "ttl out of range: $ttl" }
            lastTtl = ttl
        }

        override suspend fun setLoopbackEnabled(enabled: Boolean) {
            loopback = enabled
        }

        override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
            lastOutboundInterface = networkInterface
        }

        override fun close() = shutdown()

        fun shutdown() {
            if (closed) return
            closed = true
            inbound.close()
        }
    }

    companion object {
        const val MAX_UDP_PAYLOAD = 65507
        private val MULTICAST_CAPS =
            DatagramCapabilities(
                ecnSend = false,
                ecnReceive = false,
                dscpSend = false,
                dontFragment = false,
                hopLimitSend = false,
                hopLimitReceive = false,
                localAddressReceive = false,
                sourceAddressSelect = false,
                multicast = true,
            )
    }
}
