@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A JVM wildcard socket cannot pin a reply's source (NIO has no ancillary data), so a wildcard bind is
 * served one socket per local address — for either wildcard spelling, not only an absent host.
 */
class JvmReplySourcePinningTests {
    private val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())

    @Test
    fun aWildcardBindIsServedOneSocketPerAddress() =
        runBlocking {
            for (host in listOf(null, "::", "0.0.0.0")) {
                val served = QuicPortBinding.Own(port = 0, host = host).openServerChannel(pool, SocketPerLocalAddress)
                try {
                    assertEquals(ReplySourcePinning.SocketPerAddress, served.pinning, "host=$host")
                    assertTrue(!served.channel.localAddress.isUnspecified(), "host=$host: no member is the wildcard")
                } finally {
                    served.channel.close()
                }
            }
        }

    /**
     * The composite receives on, and answers from, each interface address a loopback client dials —
     * judged by the plain JDK client's received source. A pairing the host's own stack does not carry
     * (checked first with two plain sockets) is skipped, typed.
     */
    @Test
    fun theCompositeAnswersALoopbackClientFromEachInterfaceAddress() =
        runBlocking {
            val served = QuicPortBinding.Own(port = 0).openServerChannel(pool, SocketPerLocalAddress)
            val composite = served.channel
            try {
                val port = composite.localAddress.port
                for (dialled in interfaceAddresses()) {
                    val loopback = InetAddress.getByName(if (':' in dialled) "::1" else "127.0.0.1")
                    val target = InetAddress.getByName(dialled)
                    val carried = plainRoundTrip(loopback, target)
                    if (carried != null) {
                        recordSkip(
                            JvmReplySourcePinningTests::class,
                            SkipReason.HostBehaviourDiffers("${loopback.hostAddress} -> $dialled: $carried"),
                            SkipGate.HostCannotProvideIt("UDP between loopback and this host's $dialled"),
                        )
                        continue
                    }
                    DatagramSocket(InetSocketAddress(loopback, 0)).use { client ->
                        client.soTimeout = 2000
                        client.send(DatagramPacket(ByteArray(4), 4, InetSocketAddress(target, port)))
                        val arrived =
                            withTimeoutOrNull(2.seconds) { composite.receive() } as? DatagramReadResult.Received
                                ?: fail(
                                    "${loopback.hostAddress} -> $dialled:$port: nothing arrived at the composite (pinning=${served.pinning})",
                                )
                        val local = assertNotNull(arrived.datagram.localAddress.orNull(), "the arrival address is reported")
                        arrived.datagram.payload.freeIfNeeded()
                        assertEquals(target, InetAddress.getByName(local.host), "arrived on the socket bound to $dialled")
                        val reply =
                            BufferFactory.deterministic().allocate(4).also {
                                it.writeInt(1)
                                it.resetForRead()
                            }
                        composite.send(reply, arrived.datagram.peer, DatagramSendOptions(fromLocal = local))
                        val back = DatagramPacket(ByteArray(16), 16)
                        client.receive(back)
                        assertEquals(target, back.address, "${loopback.hostAddress} dialled $dialled; the reply came from ${back.address}")
                    }
                }
            } finally {
                composite.close()
            }
        }

    /** `null` when two plain JDK sockets carry a datagram each way between [client] and [server]; else why not. */
    private fun plainRoundTrip(
        client: InetAddress,
        server: InetAddress,
    ): String? =
        try {
            DatagramSocket(InetSocketAddress(server, 0)).use { s ->
                DatagramSocket(InetSocketAddress(client, 0)).use { c ->
                    s.soTimeout = 2000
                    c.soTimeout = 2000
                    c.send(DatagramPacket(ByteArray(4), 4, s.localSocketAddress))
                    val request = DatagramPacket(ByteArray(16), 16)
                    s.receive(request)
                    s.send(DatagramPacket(ByteArray(4), 4, request.socketAddress))
                    c.receive(DatagramPacket(ByteArray(16), 16))
                    null
                }
            }
        } catch (e: Exception) {
            "a plain round trip failed: $e"
        }

    /** The source the kernel picks for a documentation-prefix destination, per family; `connect` sends nothing. */
    private fun interfaceAddresses(): List<String> =
        listOf("192.0.2.1", "2001:db8::1").mapNotNull { target ->
            runCatching {
                DatagramSocket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getByName(target), 9))
                    socket.localAddress
                        .takeUnless { it.isAnyLocalAddress }
                        ?.hostAddress
                        ?.substringBefore('%')
                }
            }.getOrNull()
        }

    @Test
    fun theWildcardSocketItselfCannotPin() =
        runBlocking {
            val socket = QuicPortBinding.Own(port = 0).bindServerSocket(pool)
            try {
                assertEquals(ReplySourcePinning.PlatformChooses, socket.replySourcePinning())
            } finally {
                socket.close()
            }
        }
}
