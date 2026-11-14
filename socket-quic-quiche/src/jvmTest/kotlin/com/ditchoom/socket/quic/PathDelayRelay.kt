package com.ditchoom.socket.quic

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A UDP relay on `127.0.0.1` in front of [upstream] that holds every datagram for [oneWay] in each
 * direction, so a path through it has a round-trip time of twice that.
 *
 * Each client address gets a socket of its own towards [upstream]. A client that moves to a new local
 * port therefore reaches the server from a new address, exactly as it would without the relay: the
 * server sees a migration, not one unchanging peer.
 *
 * Every datagram waits the same [oneWay], so the order datagrams are queued in is the order they fall
 * due: one queue and one sender thread hold them all. Closing the relay ends and joins every thread it
 * started.
 */
internal class PathDelayRelay(
    private val upstream: InetSocketAddress,
    private val oneWay: Duration,
) : AutoCloseable {
    private val listen = DatagramSocket(InetSocketAddress("127.0.0.1", 0))

    /** Client address to the socket that carries its datagrams upstream. Written by the listen thread only. */
    private val legs = AtomicReference<Map<InetSocketAddress, DatagramSocket>>(emptyMap())
    private val held = ConcurrentLinkedQueue<Held>()
    private val threads = ConcurrentLinkedQueue<Thread>()
    private val sender = start("path-delay-relay/sender") { sendWhenDue() }

    /** The only thread that opens legs, so once it has ended no new leg can appear. */
    private val listener =
        pump("path-delay-relay/listen", listen) { packet ->
            val client = packet.socketAddress as InetSocketAddress
            hold(legs.get().getOrElse(client) { openLeg(client) }, packet, upstream)
        }

    /** The port clients send to. */
    val port: Int get() = listen.localPort

    /** For each client port seen so far, the port [upstream] sees that client's datagrams arrive from. */
    fun upstreamPorts(): Map<Int, Int> = legs.get().entries.associate { (client, leg) -> client.port to leg.localPort }

    override fun close() {
        listen.close()
        listener.join(JOIN_BUDGET.inWholeMilliseconds)
        legs.get().values.forEach { it.close() }
        LockSupport.unpark(sender)
        threads.forEach { it.join(JOIN_BUDGET.inWholeMilliseconds) }
        val alive = threads.filter { it.isAlive }.map { it.name }
        if (alive.isNotEmpty()) throw AssertionError("the relay's threads outlived it: $alive")
    }

    private fun openLeg(client: InetSocketAddress): DatagramSocket {
        val leg = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        legs.set(legs.get() + (client to leg))
        pump("path-delay-relay/leg-${client.port}", leg) { packet -> hold(listen, packet, client) }
        return leg
    }

    private fun hold(
        from: DatagramSocket,
        packet: DatagramPacket,
        to: InetSocketAddress,
    ) {
        val copy = DatagramPacket(packet.data.copyOf(packet.length), packet.length, to)
        held.add(Held(System.nanoTime() + oneWay.inWholeNanoseconds, from, copy))
        LockSupport.unpark(sender)
    }

    private fun sendWhenDue() {
        while (!listen.isClosed) {
            if (held.isEmpty()) {
                LockSupport.park(this)
                continue
            }
            // The only consumer: what was there a moment ago is still the head.
            val next = held.peek()
            val wait = next.dueNanos - System.nanoTime()
            if (wait > 0) {
                LockSupport.parkNanos(this, wait)
                continue
            }
            held.poll()
            try {
                next.from.send(next.packet)
            } catch (_: SocketException) {
                // That leg closed while the datagram was held: it had nowhere to go.
            }
        }
    }

    private fun pump(
        name: String,
        socket: DatagramSocket,
        onDatagram: (DatagramPacket) -> Unit,
    ): Thread =
        start(name) {
            val packet = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
            try {
                while (true) {
                    packet.length = MAX_DATAGRAM
                    socket.receive(packet)
                    onDatagram(packet)
                }
            } catch (_: SocketException) {
                // Closed: the pump ends with its socket.
            }
        }

    private fun start(
        name: String,
        body: () -> Unit,
    ): Thread = thread(isDaemon = true, name = name, block = body).also { threads.add(it) }

    private class Held(
        val dueNanos: Long,
        val from: DatagramSocket,
        val packet: DatagramPacket,
    )

    private companion object {
        const val MAX_DATAGRAM = 2048
        val JOIN_BUDGET = 2.seconds
    }
}
