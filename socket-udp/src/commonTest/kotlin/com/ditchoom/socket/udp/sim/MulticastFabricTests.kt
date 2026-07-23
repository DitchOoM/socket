@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.MulticastDatagramChannel
import com.ditchoom.socket.udp.MulticastException
import com.ditchoom.socket.udp.MulticastInterface
import com.ditchoom.socket.udp.MulticastMembership
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tier-A multicast semantics on the socket-free [MulticastFabric] — the deterministic oracle that runs on
 * every KMP target and pins the group-membership contract the per-platform real-socket suites then check
 * against the kernel. Every test is docker-free and virtual-time clean.
 */
@ExperimentalDatagramApi
class MulticastFabricTests {
    private val group = SocketAddress.ofLiteral("239.1.2.3", 5000)
    private val otherGroup = SocketAddress.ofLiteral("239.9.9.9", 5000)

    private fun payload(text: String): ReadBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private suspend fun MulticastDatagramChannel.recvText(): String {
        val d = (receive() as DatagramReadResult.Received).datagram
        return d.payload.readByteArray(d.payload.remaining()).decodeToString()
    }

    private fun membership(g: SocketAddress) = MulticastMembership(g)

    @Test
    fun onlyJoinedMembersReceiveAGroupDatagram() =
        runTest {
            val fabric = MulticastFabric()
            val sender = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            val joined = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 2))
            val notJoined = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 3))
            joined.joinGroup(membership(group))

            sender.send(payload("hello-group"), to = group)

            assertEquals("hello-group", joined.recvText())
            // notJoined never joined → nothing queued; a receive would block, so assert emptiness via close.
            notJoined.close()
            assertTrue(notJoined.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun everyMemberOfAGroupGetsItsOwnCopy() =
        runTest {
            val fabric = MulticastFabric()
            val sender = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            val a = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 2))
            val b = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 3))
            a.joinGroup(membership(group))
            b.joinGroup(membership(group))

            sender.send(payload("fanout"), to = group)

            assertEquals("fanout", a.recvText())
            assertEquals("fanout", b.recvText())
            fabric.close()
        }

    @Test
    fun distinctGroupsDoNotCrossDeliver() =
        runTest {
            val fabric = MulticastFabric()
            val sender = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            val onGroup = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 2))
            val onOther = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 3))
            onGroup.joinGroup(membership(group))
            onOther.joinGroup(membership(otherGroup))

            sender.send(payload("for-group"), to = group)

            assertEquals("for-group", onGroup.recvText())
            onOther.close()
            assertTrue(onOther.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun leaveGroupStopsFurtherDelivery() =
        runTest {
            val fabric = MulticastFabric()
            val sender = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            val member = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 2))
            member.joinGroup(membership(group))

            sender.send(payload("first"), to = group)
            assertEquals("first", member.recvText())

            member.leaveGroup(membership(group))
            sender.send(payload("second"), to = group)

            member.close()
            // "second" must not have been delivered — the next read is Closed, not "second".
            assertTrue(member.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun loopbackEnabledDeliversSendersOwnCopyWhenItJoined() =
        runTest {
            val fabric = MulticastFabric()
            val sender = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            sender.joinGroup(membership(group)) // sender is also a member
            sender.send(payload("echo"), to = group)
            assertEquals("echo", sender.recvText()) // loopback default on → self-copy delivered
            fabric.close()
        }

    @Test
    fun loopbackDisabledSuppressesOnlyTheSelfCopy() =
        runTest {
            val fabric = MulticastFabric()
            val sender = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            val other = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 2))
            sender.joinGroup(membership(group))
            other.joinGroup(membership(group))
            sender.setLoopbackEnabled(false)

            sender.send(payload("no-self"), to = group)

            // The other member still receives it; only the sender's self-copy is suppressed.
            assertEquals("no-self", other.recvText())
            sender.close()
            assertTrue(sender.receive() is DatagramReadResult.Closed)
            fabric.close()
        }

    @Test
    fun leaveWithoutJoinIsTypedFailure() =
        runTest {
            val fabric = MulticastFabric()
            val e = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            assertFailsWith<MulticastException> { e.leaveGroup(membership(group)) }
            fabric.close()
        }

    @Test
    fun controlKnobsAreRecordedAndAdvertised() =
        runTest {
            val fabric = MulticastFabric()
            val e = fabric.open(SocketAddress.ofLiteral("127.0.0.1", 1))
            assertTrue(e.capabilities.multicast)
            e.setTimeToLive(4)
            e.setOutboundInterface(MulticastInterface.ByName("lo"))
            assertFailsWith<IllegalArgumentException> { e.setTimeToLive(999) }
            fabric.close()
        }
}
