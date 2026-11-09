package com.ditchoom.socket.quic

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A proxy that takes a server's reply away from the client **whatever the server's timing**, for a test
 * that needs the reply lost and the server's CONNECTION_CLOSE delivered.
 *
 * Once [arm]ed, no server→client datagram is forwarded as it arrives. Each one replaces the last one
 * held. After [serverHandlerReturned], whenever the server has sent nothing for [quiet], the held
 * datagram is delivered to the client, once. A server that closed sends nothing after its
 * CONNECTION_CLOSE, so the datagram delivered after it goes quiet is that close. Client→server
 * datagrams are always forwarded.
 *
 * Why not "drop the first N datagrams": that leaves the result to a race. A count window that ends
 * one datagram before the server's close lets the reply's last datagram through. The client ACKs it,
 * quiche's packet-threshold loss detection marks everything before it lost, and the server
 * retransmits the whole reply within one round trip, unless its close has already gone out. When that
 * happens the client reads the entire reply, which is how the count window failed on CI.
 *
 * Here the reply can reach the client only if its first datagram, the one at stream offset 0, is the
 * last thing the server sends before a [quiet] gap. The server sends its reply as one burst, offset 0
 * first, and then closes, so that does not happen.
 *
 * Test-only: `ByteBuffer`/`ByteArray` are fine here.
 */
internal class ReplyBlackoutProxy(
    serverPort: Int,
    private val quiet: Duration,
) : ImpairingProxy {
    private val lock = Any()

    // Guarded by [lock].
    private var armed = false
    private var serverDone = false
    private var held: ByteArray? = null
    private var lastHeldAtNanos = 0L
    private var dropped = 0
    private var delivered = 0

    private val releaser =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "reply-blackout-release").apply { isDaemon = true } }

    private val relay =
        SelectorDatagramRelay(
            serverPort = serverPort,
            maxDatagram = MAX_DATAGRAM,
            onClientToServer = { buf, _ -> relayToServer(buf) },
            onServerToClient = { buf, n -> fromServer(buf, n) },
        )

    override val proxyPort: Int get() = relay.proxyPort

    /** Server→client datagrams kept from the client: every one received since [arm], whether or not it was later delivered. */
    override val droppedCount: Int get() = synchronized(lock) { dropped }

    /** How many held datagrams were delivered after the server went quiet. */
    val deliveredAfterQuietCount: Int get() = synchronized(lock) { delivered }

    override val duplicatedCount: Int get() = 0
    override val delayedCount: Int get() = 0
    override val reorderedCount: Int get() = 0

    init {
        relay.start()
        val pollMs = POLL.inWholeMilliseconds
        releaser.scheduleWithFixedDelay({ releaseIfQuiet() }, pollMs, pollMs, TimeUnit.MILLISECONDS)
    }

    override fun arm() {
        synchronized(lock) { armed = true }
    }

    /** The server's handler has returned, so its close is under way: from now on a quiet server's last datagram is delivered. */
    fun serverHandlerReturned() {
        synchronized(lock) { serverDone = true }
    }

    private fun relayToServer(buf: ByteBuffer) {
        relay.writeToServer(buf)
    }

    private fun fromServer(
        buf: ByteBuffer,
        n: Int,
    ) {
        synchronized(lock) {
            if (armed) {
                val copy = ByteArray(n)
                buf.duplicate().get(copy)
                held = copy
                lastHeldAtNanos = System.nanoTime()
                dropped++
                return
            }
        }
        relay.writeToClient(buf)
    }

    private fun releaseIfQuiet() {
        val toDeliver =
            synchronized(lock) {
                val pending = held
                if (!serverDone || pending == null || System.nanoTime() - lastHeldAtNanos < quiet.inWholeNanoseconds) return
                held = null
                delivered++
                pending
            }
        relay.writeToClientBytes(toDeliver)
    }

    override suspend fun close() {
        releaser.shutdownNow()
        relay.close()
    }

    private companion object {
        const val MAX_DATAGRAM = 2048
        val POLL = 10.milliseconds
    }
}
