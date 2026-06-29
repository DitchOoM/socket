package com.ditchoom.socket.quic

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Non-blocking userspace UDP relay shared by every JVM + Android QUIC test proxy (impairment, passive
 * migration). Client ↔ [proxyPort] ↔ server, one daemon thread.
 *
 * **Why it exists — making a whole class of teardown flake impossible.** The hand-rolled proxies used
 * to pump each direction on a daemon thread blocked in a *blocking* [DatagramChannel.receive] /
 * [DatagramChannel.read], then `close()` the channels from the test thread to tear down. Closing an
 * interruptible channel while another thread is blocked in its native read makes the JDK/Android close
 * path call `sun.nio.ch.NativeThread.signal`, which intermittently throws `java.io.IOException: Success`
 * (errno 0 mis-mapped) — observed only on teardown, so it fails an already-passed test at random.
 * Swallowing it would only hide the race.
 *
 * Here the channels are **non-blocking** and the thread only ever parks in [Selector.select]; it is
 * never blocked in a channel read. Teardown sets `running = false`, [Selector.wakeup]s, **joins** the
 * pump, and only THEN closes the channels — with no thread blocked in a read, `NativeThread.signal` is
 * never invoked, so the `IOException: Success` race cannot occur (not merely caught). [rebindUpstream]
 * is the same discipline: the upstream swap runs on the pump thread via [Selector.wakeup], so the old
 * channel is closed while the only reader is parked in `select()`, not in a read.
 *
 * The relay is policy-agnostic: it surfaces each received datagram to a per-direction callback (invoked
 * on the pump thread) and exposes thread-safe emit helpers; the proxies keep their own impairment /
 * rebind semantics. `ByteBuffer` / `ByteArray` are fine here — test-only.
 */
internal class SelectorDatagramRelay(
    private val serverPort: Int,
    maxDatagram: Int,
    private val onClientToServer: (ByteBuffer, Int) -> Unit,
    private val onServerToClient: (ByteBuffer, Int) -> Unit,
) {
    private val selector = Selector.open()

    private val clientChannel =
        DatagramChannel.open().apply {
            configureBlocking(false)
            bind(InetSocketAddress("127.0.0.1", 0))
            register(selector, SelectionKey.OP_READ)
        }

    /** The client-facing local port — the address the test points its QUIC client at. */
    val proxyPort: Int = (clientChannel.localAddress as InetSocketAddress).port

    @Volatile private var upstream: DatagramChannel = openUpstream()

    @Volatile private var clientAddr: SocketAddress? = null

    @Volatile private var running = true

    // Actions to run on the pump thread (the upstream rebind). Drained right after each select() wake,
    // so channel mutation never races the reader — there is only ever one reader, this thread.
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private val clientBuf = ByteBuffer.allocate(maxDatagram)
    private val serverBuf = ByteBuffer.allocate(maxDatagram)

    private fun openUpstream(): DatagramChannel =
        DatagramChannel.open().apply {
            configureBlocking(false)
            connect(InetSocketAddress("127.0.0.1", serverPort))
            register(selector, SelectionKey.OP_READ)
        }

    private val pump =
        thread(isDaemon = true, name = "relay-pump", start = false) {
            while (running) {
                val ready =
                    try {
                        selector.select(POLL_MS)
                    } catch (_: Exception) {
                        if (!running) break else continue
                    }
                if (!running) break
                while (true) (pending.poll() ?: break).invoke()
                if (ready == 0) continue
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid || !key.isReadable) continue
                    val ch = key.channel() as DatagramChannel
                    if (ch === clientChannel) {
                        clientBuf.clear()
                        val from = guardedReceive(ch, clientBuf) ?: continue
                        clientAddr = from
                        clientBuf.flip()
                        onClientToServer(clientBuf, clientBuf.remaining())
                    } else if (ch === upstream) {
                        serverBuf.clear()
                        val n = guardedRead(ch, serverBuf)
                        if (n > 0) {
                            serverBuf.flip()
                            onServerToClient(serverBuf, n)
                        }
                    }
                }
            }
        }

    fun start() {
        pump.start()
    }

    /** Forward the (positioned) buffer toward the server. Thread-safe (callable from a delayed-send scheduler). */
    fun writeToServer(b: ByteBuffer) = guarded { upstream.write(b) }

    fun writeToServerBytes(a: ByteArray) = guarded { upstream.write(ByteBuffer.wrap(a)) }

    /** Forward toward the client's last-seen source address. Thread-safe. No-op until a client datagram arrives. */
    fun writeToClient(b: ByteBuffer) = guarded { clientAddr?.let { clientChannel.send(b, it) } }

    fun writeToClientBytes(a: ByteArray) = guarded { clientAddr?.let { clientChannel.send(ByteBuffer.wrap(a), it) } }

    /**
     * Passive NAT rebind: swap the upstream for one with a fresh source port so the server sees the same
     * connection arrive from a new 4-tuple. Performed on the pump thread (via [Selector.wakeup]) so the old
     * channel is closed while its only reader is parked in `select()`, never in a read.
     */
    fun rebindUpstream() {
        pending.add {
            val old = upstream
            upstream = openUpstream()
            old.keyFor(selector)?.cancel()
            closeQuietly(old)
        }
        selector.wakeup()
    }

    /** Race-free teardown: stop the pump and JOIN it before closing any channel (see the class KDoc). */
    fun close() {
        running = false
        selector.wakeup()
        try {
            pump.join(CLOSE_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly(clientChannel)
        closeQuietly(upstream)
        try {
            selector.close()
        } catch (_: Exception) {
            // teardown — nothing to recover.
        }
    }

    private fun guardedReceive(
        ch: DatagramChannel,
        buf: ByteBuffer,
    ): SocketAddress? =
        try {
            ch.receive(buf)
        } catch (_: Exception) {
            null
        }

    private fun guardedRead(
        ch: DatagramChannel,
        buf: ByteBuffer,
    ): Int =
        try {
            ch.read(buf)
        } catch (_: Exception) {
            -1
        }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Best-effort forward — a closed/rebinding channel is expected around teardown/migration.
        }
    }

    private fun closeQuietly(ch: DatagramChannel) {
        try {
            ch.close()
        } catch (_: Exception) {
            // teardown — nothing to recover.
        }
    }

    private companion object {
        private const val POLL_MS = 50L
        private const val CLOSE_JOIN_MS = 2000L
    }
}
