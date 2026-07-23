@file:OptIn(ExperimentalDatagramApi::class, DelicateCoroutinesApi::class)

package com.ditchoom.socket.udp.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.testkit.fault.ByteEdit
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.testkit.fault.ImpairmentEngine
import com.ditchoom.socket.testkit.fault.UnitDecision
import com.ditchoom.socket.udp.MulticastDatagramChannel
import com.ditchoom.socket.udp.MulticastException
import com.ditchoom.socket.udp.MulticastInterface
import com.ditchoom.socket.udp.MulticastMembership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * A deterministic, **socket-free** multicast bus — the Tier-A semantic oracle for
 * [MulticastDatagramChannel] (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §2), and the multicast analogue of the
 * unicast [ImpairedDatagramPipe]. Several in-memory endpoints share one logical subnet; a datagram [send]t
 * to a group fans out — in FIFO order, as an isolated copy carrying the **sender's** address as its
 * [Datagram.peer] — to exactly the endpoints that [joinGroup]'d that group *on a matching interface*, and
 * to no one else. It reproduces bit-for-bit under `runTest` virtual time, with none of the CI-container
 * multicast-routing flakiness real sockets bring:
 *
 * - **join is required to receive**, **leave stops delivery**, **groups are isolated**.
 * - **loopback** — a sender that itself joined the group receives its own copy iff its loopback is enabled.
 * - **interface scoping** — a send egresses on the sender's [setOutboundInterface] interface and reaches
 *   only members that joined the group on that interface, with [MulticastInterface.Default] acting as a
 *   wildcard on either side (the kernel-default case) — see [reaches].
 * - **fault injection** — each endpoint owns a per-member [ImpairmentEngine] (its own seeded draw
 *   sequence) applied to its *incoming* copies, so a [FaultSchedule] deterministically drops / duplicates /
 *   delays / corrupts what that member receives — the same neutral schedule that drives the Tier-C relay.
 *
 * [setTimeToLive] is range-checked (an out-of-range TTL is the same [IllegalArgumentException] the real
 * channels throw) but does not gate delivery here — hop scope is one subnet; its real `setsockopt` effect
 * is covered by the per-platform control-plane suites.
 *
 * The [scope] is the `runTest` scope: the clean fan-out is synchronous, but a [FaultSchedule] that delays
 * or duplicates a copy schedules it on [scope], so `advanceUntilIdle()` lands it. Confine each endpoint's
 * `receive`/`send` to one coroutine, like a real channel.
 *
 * [bufferFactory] is injected (the allocate-and-transfer hook, exactly as the real channels take it): each
 * delivered [Datagram]'s payload is allocated from it and the source bytes are copied buffer→buffer via the
 * SIMD `xorMaskCopy` primitive — no `ByteArray` ever materializes, so the harness exercises the same buffer
 * path a real consumer does. Ownership of a delivered payload transfers to whoever reads the [Datagram], so
 * a consumer must `freeNativeMemory()` it; any payload never read (endpoint closed, delayed copy racing a
 * [close]) is freed by the fabric itself, so a native-memory factory leaks nothing.
 */
@ExperimentalDatagramApi
class MulticastFabric(
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
) {
    private val endpoints = mutableListOf<Endpoint>()

    /**
     * Open a new endpoint on the bus, identified by [localAddress]. [faults] impairs the datagrams THIS
     * endpoint *receives* (its link), default clean.
     */
    fun open(
        localAddress: SocketAddress,
        faults: FaultSchedule = FaultSchedule.CLEAN,
    ): MulticastDatagramChannel = Endpoint(localAddress, faults).also { endpoints.add(it) }

    /** Close every endpoint; parked `receive` calls then observe [DatagramReadResult.Closed]. */
    fun close() = endpoints.forEach { it.close() }

    /** A group joined on a specific interface — the membership identity (interface-scoped). */
    private data class GroupOnInterface(
        val group: String,
        val iface: MulticastInterface,
    )

    private inner class Endpoint(
        override val localAddress: SocketAddress,
        faults: FaultSchedule,
    ) : MulticastDatagramChannel {
        private val joined = mutableSetOf<GroupOnInterface>()
        private var loopback = true
        private var outboundInterface: MulticastInterface = MulticastInterface.Default
        private val engine = ImpairmentEngine(faults)

        // The inbound queue is the ONE source of truth for this endpoint's lifetime: open == still
        // send-able. UNLIMITED so a send never suspends on a slow reader; FIFO is the ordering guarantee.
        private val inbound = Channel<Datagram>(Channel.UNLIMITED)

        override val isOpen: Boolean get() = !inbound.isClosedForSend
        override val maxWritableSize: Int = MAX_UDP_PAYLOAD
        override val capabilities: DatagramCapabilities = MULTICAST_CAPS

        override suspend fun receive(): DatagramReadResult {
            val datagram = inbound.receiveCatching().getOrNull() ?: return DatagramReadResult.Closed()
            return DatagramReadResult.Received(datagram)
        }

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress?,
            options: DatagramSendOptions,
        ) {
            check(isOpen) { "sink is closed" }
            // A multicast channel is unconnected: a send with no destination is a caller error, exactly like
            // the real actuals' send(to = null) on an unconnected channel.
            val group = checkNotNull(to) { "no destination: a multicast send requires a group address" }
            val egress = outboundInterface
            for (member in endpoints) {
                if (!member.isOpen) continue // a closed endpoint has no live receive link
                if (!member.receives(group.host, egress)) continue // not joined on a matching interface
                if (member === this && !loopback) continue // sender's own copy, loopback disabled
                when (val decision = member.engine.decide()) {
                    UnitDecision.Dropped -> {} // this member's link dropped it
                    is UnitDecision.Delivered ->
                        // Each copy is delivered by the receiving member, which allocates it from ITS injected
                        // factory and applies this copy's edits — buffer-native, no ByteArray.
                        decision.copies.forEach { copy ->
                            member.deliver(payload, copy.edits, sender = localAddress, after = copy.afterDelay)
                        }
                }
            }
        }

        /** True iff this endpoint has joined [groupHost] on an interface that a send out [egress] reaches. */
        private fun receives(
            groupHost: String,
            egress: MulticastInterface,
        ): Boolean = joined.any { it.group == groupHost && it.iface.reaches(egress) }

        /**
         * Allocate an isolated copy of [source]'s remaining bytes from THIS member's injected factory via the
         * SIMD `xorMaskCopy` primitive (no ByteArray), apply this copy's [edits], and deliver it as a
         * [Datagram] whose [Datagram.peer] is the [sender]. [source] is not consumed (a fresh `slice()` view
         * is read). A positive [after] holds the copy on [scope] (a delayed/duplicated fault).
         */
        fun deliver(
            source: ReadBuffer,
            edits: List<ByteEdit>,
            sender: SocketAddress,
            after: Duration,
        ) {
            val src = source.slice()
            val len = src.remaining()
            val payload = bufferFactory.allocate(len)
            // Buffer→buffer copy through the SIMD xorMaskCopy primitive (mask 0 = verbatim) — no ByteArray
            // ever materializes, so the harness drives the same buffer path a real consumer does.
            payload.xorMaskCopy(src, mask = 0)
            // Each ByteEdit is a single-byte deterministic corruption: flip flipMask's bits of the byte at
            // offset via a positioned buffer read-modify-write (an offset past the copy is a no-op, matching
            // Fault.Corrupt's "short units pass through" contract). Positioned set stays buffer-native.
            for (edit in edits) {
                if (edit.offset in 0 until len) {
                    payload.set(edit.offset, (payload.get(edit.offset).toInt() xor edit.flipMask).toByte())
                }
            }
            payload.resetForRead()
            val datagram = Datagram(payload = payload, peer = sender)
            if (after > Duration.ZERO) {
                scope.launch {
                    delay(after)
                    enqueue(datagram)
                }
            } else {
                enqueue(datagram) // clean path stays synchronous → strictly FIFO
            }
        }

        /**
         * Hand [datagram] to this endpoint's consumer. If the endpoint has already been closed the consumer
         * will never read it — [Channel.trySend] then fails, so the payload (allocated from the injected
         * factory) is freed here rather than leaked. This mirrors the real channels' free-on-non-delivery.
         */
        private fun enqueue(datagram: Datagram) {
            if (inbound.trySend(datagram).isFailure) datagram.payload.freeNativeMemory()
        }

        override suspend fun joinGroup(membership: MulticastMembership) {
            if (!isOpen) {
                throw MulticastException.JoinFailed(membership.group, membership.networkInterface, "channel closed")
            }
            val key = GroupOnInterface(membership.group.host, membership.networkInterface)
            if (!joined.add(key)) {
                throw MulticastException.JoinFailed(membership.group, membership.networkInterface, "already joined")
            }
        }

        override suspend fun leaveGroup(membership: MulticastMembership) {
            val key = GroupOnInterface(membership.group.host, membership.networkInterface)
            if (!joined.remove(key)) {
                throw MulticastException.LeaveFailed(membership.group, membership.networkInterface, "not joined")
            }
        }

        override suspend fun setTimeToLive(ttl: Int) {
            require(ttl in 0..255) { "ttl out of range: $ttl" }
        }

        override suspend fun setLoopbackEnabled(enabled: Boolean) {
            loopback = enabled
        }

        override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
            outboundInterface = networkInterface
        }

        override fun close() {
            if (!inbound.close()) return // idempotent: already closed
            // A closed channel still yields its buffered elements; reclaim every delivered-but-unread payload
            // (ownership never reached a consumer) so a native-memory factory leaks nothing.
            generateSequence { inbound.tryReceive().getOrNull() }
                .forEach { it.payload.freeNativeMemory() }
        }
    }

    companion object {
        const val MAX_UDP_PAYLOAD = 65507

        /**
         * Interface match with [MulticastInterface.Default] as a wildcard on *either* side: two concrete
         * interfaces reach each other only when equal, but a Default egress reaches every member and a
         * Default membership is reached by every egress — the kernel-default routing case.
         */
        private fun MulticastInterface.reaches(other: MulticastInterface): Boolean =
            this == other || this == MulticastInterface.Default || other == MulticastInterface.Default

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
