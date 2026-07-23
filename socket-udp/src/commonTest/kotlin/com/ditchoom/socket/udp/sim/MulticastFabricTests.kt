@file:OptIn(ExperimentalDatagramApi::class, ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.udp.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.OutboundDatagram
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.udp.MulticastDatagramChannel
import com.ditchoom.socket.udp.MulticastException
import com.ditchoom.socket.udp.MulticastInterface
import com.ditchoom.socket.udp.MulticastMembership
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tier-A multicast semantics on the socket-free [MulticastFabric] — the deterministic oracle that runs on
 * every KMP target and pins the group-membership contract the per-platform real-socket suites then check
 * against the kernel. Every test is docker-free and virtual-time clean.
 *
 * Coverage is three-layered: the group-membership contract (join/leave/isolation/loopback/interface
 * scoping), the [FaultSchedule] fault-injection actions (drop / duplicate / delay / corrupt) applied to a
 * member's receive link, and the buffer-lifecycle discipline (every allocated payload is freed) asserted
 * end-to-end via a [LeakTrackingBufferFactory].
 */
@ExperimentalDatagramApi
class MulticastFabricTests {
    private val group = SocketAddress.ofLiteral("239.1.2.3", 5000)
    private val otherGroup = SocketAddress.ofLiteral("239.9.9.9", 5000)
    private val v6Group = SocketAddress.ofLiteral("ff02::1234", 5000)

    private val eth0 = MulticastInterface.ByName("eth0")
    private val eth1 = MulticastInterface.ByName("eth1")

    private fun addr(port: Int): SocketAddress = SocketAddress.ofLiteral("127.0.0.1", port)

    private fun payload(text: String): PlatformBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private fun membership(
        g: SocketAddress,
        iface: MulticastInterface = MulticastInterface.Default,
    ) = MulticastMembership(g, iface)

    /** Send [text] to [to], then free the sent payload — the sink never takes ownership of it. */
    private suspend fun MulticastDatagramChannel.sendText(
        text: String,
        to: SocketAddress,
    ) {
        val p = payload(text)
        send(p, to = to)
        p.freeNativeMemory()
    }

    /** Receive one datagram, decode it, and free its payload — ownership of a received payload is ours. */
    private suspend fun MulticastDatagramChannel.recvText(): String {
        val d = (receive() as DatagramReadResult.Received).datagram
        val text = d.payload.readByteArray(d.payload.remaining()).decodeToString()
        d.payload.freeNativeMemory()
        return text
    }

    // ---- membership contract ----

    @Test
    fun onlyJoinedMembersReceiveAGroupDatagram() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val joined = fabric.open(addr(2))
            val notJoined = fabric.open(addr(3))
            joined.joinGroup(membership(group))

            sender.sendText("hello-group", group)

            assertEquals("hello-group", joined.recvText())
            // notJoined never joined → nothing queued; a receive would block, so assert emptiness via close.
            notJoined.close()
            assertTrue(notJoined.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun everyMemberOfAGroupGetsItsOwnCopy() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val a = fabric.open(addr(2))
            val b = fabric.open(addr(3))
            a.joinGroup(membership(group))
            b.joinGroup(membership(group))

            sender.sendText("fanout", group)

            assertEquals("fanout", a.recvText())
            assertEquals("fanout", b.recvText())
            fabric.close()
        }

    @Test
    fun distinctGroupsDoNotCrossDeliver() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val onGroup = fabric.open(addr(2))
            val onOther = fabric.open(addr(3))
            onGroup.joinGroup(membership(group))
            onOther.joinGroup(membership(otherGroup))

            sender.sendText("for-group", group)

            assertEquals("for-group", onGroup.recvText())
            onOther.close()
            assertTrue(onOther.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun leaveGroupStopsFurtherDelivery() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2))
            member.joinGroup(membership(group))

            sender.sendText("first", group)
            assertEquals("first", member.recvText())

            member.leaveGroup(membership(group))
            sender.sendText("second", group)

            member.close()
            // "second" must not have been delivered — the next read is Closed, not "second".
            assertTrue(member.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun rejoiningAfterLeaveResumesDelivery() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2))
            member.joinGroup(membership(group))
            member.leaveGroup(membership(group))
            member.joinGroup(membership(group)) // rejoin is legal after a clean leave

            sender.sendText("again", group)
            assertEquals("again", member.recvText())
            fabric.close()
        }

    @Test
    fun loopbackEnabledDeliversSendersOwnCopyWhenItJoined() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            sender.joinGroup(membership(group)) // sender is also a member
            sender.sendText("echo", group)
            assertEquals("echo", sender.recvText()) // loopback default on → self-copy delivered
            fabric.close()
        }

    @Test
    fun loopbackDisabledSuppressesOnlyTheSelfCopy() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val other = fabric.open(addr(2))
            sender.joinGroup(membership(group))
            other.joinGroup(membership(group))
            sender.setLoopbackEnabled(false)

            sender.sendText("no-self", group)

            // The other member still receives it; only the sender's self-copy is suppressed.
            assertEquals("no-self", other.recvText())
            sender.close()
            assertTrue(sender.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun deliveredDatagramCarriesTheSendersAddressAsPeer() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val senderAddr = addr(7)
            val sender = fabric.open(senderAddr)
            val member = fabric.open(addr(2))
            member.joinGroup(membership(group))

            sender.sendText("who-sent-this", group)

            val d = (member.receive() as DatagramReadResult.Received).datagram
            assertEquals(senderAddr, d.peer)
            assertEquals("who-sent-this", d.payload.readByteArray(d.payload.remaining()).decodeToString())
            d.payload.freeNativeMemory()
            fabric.close()
        }

    @Test
    fun deliveryIsFifoPerReceiver() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2))
            member.joinGroup(membership(group))

            repeat(5) { sender.sendText("m$it", group) }

            (0 until 5).forEach { assertEquals("m$it", member.recvText()) }
            fabric.close()
        }

    @Test
    fun sendBatchFansEachDatagramOutToTheGroup() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2))
            member.joinGroup(membership(group))

            val p0 = payload("b0")
            val p1 = payload("b1")
            val p2 = payload("b2")
            sender.sendBatch(
                listOf(
                    OutboundDatagram(p0, group),
                    OutboundDatagram(p1, group),
                    OutboundDatagram(p2, group),
                ),
            )

            assertEquals("b0", member.recvText())
            assertEquals("b1", member.recvText())
            assertEquals("b2", member.recvText())
            p0.freeNativeMemory()
            p1.freeNativeMemory()
            p2.freeNativeMemory()
            fabric.close()
        }

    // ---- IPv6 ----

    @Test
    fun ipv6GroupsDeliverAndStayIsolatedFromIpv4() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val onV6 = fabric.open(addr(2))
            val onV4 = fabric.open(addr(3))
            onV6.joinGroup(membership(v6Group))
            onV4.joinGroup(membership(group)) // an IPv4 group, must not receive the ff02:: send

            sender.sendText("v6-only", v6Group)

            assertEquals("v6-only", onV6.recvText())
            onV4.close()
            assertTrue(onV4.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    // ---- interface scoping ----

    @Test
    fun aSendEgressingOneInterfaceReachesOnlyThatInterfaceOrDefaultMembers() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            sender.setOutboundInterface(eth0)
            val onEth0 = fabric.open(addr(2))
            val onEth1 = fabric.open(addr(3))
            val onDefault = fabric.open(addr(4))
            onEth0.joinGroup(membership(group, eth0))
            onEth1.joinGroup(membership(group, eth1))
            onDefault.joinGroup(membership(group, MulticastInterface.Default))

            sender.sendText("scoped", group)

            assertEquals("scoped", onEth0.recvText()) // matching interface
            assertEquals("scoped", onDefault.recvText()) // Default acts as a wildcard on the member side
            onEth1.close()
            assertTrue(onEth1.receive() is DatagramReadResult.Closed) // eth1 mismatch → never delivered
            fabric.close()
        }

    @Test
    fun aDefaultEgressReachesMembersOnAnyInterface() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1)) // egress stays Default (wildcard)
            val onEth0 = fabric.open(addr(2))
            onEth0.joinGroup(membership(group, eth0))

            sender.sendText("wildcard-egress", group)

            assertEquals("wildcard-egress", onEth0.recvText())
            fabric.close()
        }

    // ---- fault injection ----

    @Test
    fun aDroppedDatagramIsNotDeliveredButLaterOnesAre() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2), faults = FaultSchedule { drop(nth = 0) })
            member.joinGroup(membership(group))

            sender.sendText("dropped", group) // unit 0 on this member's link → dropped
            sender.sendText("kept", group) // unit 1 → delivered

            assertEquals("kept", member.recvText())
            fabric.close()
        }

    @Test
    fun aDuplicatedDatagramArrivesTwiceInOrder() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2), faults = FaultSchedule { duplicate(nth = 0) })
            member.joinGroup(membership(group))

            sender.sendText("twice", group)
            advanceUntilIdle() // the duplicate trails by a spacing delay

            assertEquals("twice", member.recvText())
            assertEquals("twice", member.recvText())
            fabric.close()
        }

    @Test
    fun aDelayedDatagramIsHeldForItsDuration() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member = fabric.open(addr(2), faults = FaultSchedule { delay(50.milliseconds) })
            member.joinGroup(membership(group))

            sender.sendText("later", group)
            // receive() suspends; the test scheduler auto-advances virtual time to fire the held delivery.
            assertEquals("later", member.recvText())
            assertEquals(50L, testScheduler.currentTime) // proves the 50 ms hold was actually applied
            fabric.close()
        }

    @Test
    fun aCorruptedDatagramHasExactlyItsTargetByteFlipped() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val member =
                fabric.open(addr(2), faults = FaultSchedule { corrupt(nth = 0, offset = 1, flipMask = 0xFF) })
            member.joinGroup(membership(group))

            sender.sendText("AAAA", group)

            val d = (member.receive() as DatagramReadResult.Received).datagram
            val bytes = d.payload.readByteArray(d.payload.remaining())
            d.payload.freeNativeMemory()
            assertEquals('A'.code.toByte(), bytes[0]) // untouched
            assertEquals(('A'.code xor 0xFF).toByte(), bytes[1]) // flipped by 0xFF
            assertEquals('A'.code.toByte(), bytes[2]) // untouched
            assertEquals('A'.code.toByte(), bytes[3]) // untouched
            fabric.close()
        }

    // ---- typed control-plane failures ----

    @Test
    fun leaveWithoutJoinIsATypedLeaveFailure() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val e = fabric.open(addr(1))
            assertFailsWith<MulticastException.LeaveFailed> { e.leaveGroup(membership(group)) }
            fabric.close()
        }

    @Test
    fun joiningTheSameGroupTwiceIsATypedJoinFailure() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val member = fabric.open(addr(1))
            member.joinGroup(membership(group))
            assertFailsWith<MulticastException.JoinFailed> { member.joinGroup(membership(group)) }
            fabric.close()
        }

    @Test
    fun sendWithoutADestinationIsACallerError() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val sender = fabric.open(addr(1))
            val p = payload("no-dest")
            assertFailsWith<IllegalStateException> { sender.send(p, to = null) }
            p.freeNativeMemory()
            fabric.close()
        }

    @Test
    fun controlKnobsAreRecordedAndAdvertised() =
        runTest {
            val fabric = MulticastFabric(BufferFactory.Default, scope = this)
            val e = fabric.open(addr(1))
            assertTrue(e.capabilities.multicast)
            e.setTimeToLive(4)
            e.setOutboundInterface(MulticastInterface.ByName("lo"))
            assertFailsWith<IllegalArgumentException> { e.setTimeToLive(999) }
            fabric.close()
        }

    // ---- buffer-lifecycle discipline ----

    @Test
    fun everyAllocatedPayloadIsFreedWhetherReadOrNot() =
        runTest {
            val factory = LeakTrackingBufferFactory()
            val fabric = MulticastFabric(factory, scope = this)
            val reader = fabric.open(addr(2))
            val duplicating = fabric.open(addr(3), faults = FaultSchedule { duplicate(nth = 0) })
            val neverReads = fabric.open(addr(4))
            reader.joinGroup(membership(group))
            duplicating.joinGroup(membership(group))
            neverReads.joinGroup(membership(group))
            val sender = fabric.open(addr(1))

            sender.sendText("lifecycle", group) // reader:1 copy, duplicating:2 copies, neverReads:1 copy
            advanceUntilIdle() // land the trailing duplicate

            reader.recvText() // frees 1
            duplicating.recvText() // frees 1
            duplicating.recvText() // frees the duplicate
            // neverReads leaves its copy queued — fabric.close() must reclaim it.
            fabric.close()

            assertEquals(0, factory.live, "every payload allocated for a delivery must be freed")
        }
}

/**
 * A [BufferFactory] that counts how many [allocate]d buffers are still un-freed, so a test can assert the
 * fabric leaks nothing. Each allocation is wrapped so its [PlatformBuffer.freeNativeMemory] decrements the
 * live count exactly once; [wrap] is passed through untracked (sent payloads are the caller's, not the
 * fabric's, to free). Single-thread test use only — the counter is unsynchronized.
 */
@ExperimentalDatagramApi
private class LeakTrackingBufferFactory(
    private val delegate: BufferFactory = BufferFactory.Default,
) : BufferFactory {
    private var outstanding = 0

    /** Payloads allocated through this factory that have not yet been freed. */
    val live: Int get() = outstanding

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        outstanding++
        return Tracked(delegate.allocate(size, byteOrder)) { outstanding-- }
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)

    private class Tracked(
        private val inner: PlatformBuffer,
        private val onFree: () -> Unit,
    ) : PlatformBuffer by inner {
        private var freed = false

        override fun freeNativeMemory() {
            if (!freed) {
                freed = true
                onFree()
            }
            inner.freeNativeMemory()
        }

        // Delegation would forward slice() to inner (correct), but spell it out so the narrowed
        // PlatformBuffer return type is unambiguous.
        override fun slice(byteOrder: ByteOrder): PlatformBuffer = inner.slice(byteOrder)
    }
}
