package com.ditchoom.socket.quic

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * Open a fresh upstream socket toward the server.
     *
     * [DatagramChannel.connect] on an unbound channel implicitly binds to a kernel-chosen ephemeral
     * port, and that port can reproduce the 4-tuple of a socket this JVM already holds — most easily
     * the upstream being replaced, but any of the long-lived test JVM's other loopback sockets will
     * do. BSD answers a duplicate tuple with `EADDRINUSE` rather than picking another port, so this
     * is a *retryable* collision, not a real conflict: observed on a macOS CI runner as
     * `BindException: Address already in use` out of [rebindUpstream].
     *
     * Each attempt needs its own channel — a failed `connect` leaves the previous one bound.
     */
    private fun openUpstream(): DatagramChannel {
        var last: Exception? = null
        repeat(OPEN_UPSTREAM_ATTEMPTS) {
            val ch = DatagramChannel.open()
            try {
                ch.configureBlocking(false)
                ch.connect(InetSocketAddress("127.0.0.1", serverPort))
                ch.register(selector, SelectionKey.OP_READ)
                return ch
            } catch (e: Exception) {
                closeQuietly(ch)
                last = e
            }
        }
        throw IllegalStateException(
            "relay upstream connect to 127.0.0.1:$serverPort failed after $OPEN_UPSTREAM_ATTEMPTS attempts",
            last,
        )
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
                while (true) {
                    val action = pending.poll() ?: break
                    try {
                        action.invoke()
                    } catch (_: Exception) {
                        // The pump must survive a failing action. Every action already reports its
                        // own outcome to the caller that queued it (see rebindUpstream), so there is
                        // nothing to recover here — but letting a throw escape would kill this thread
                        // and silently stop relaying in BOTH directions, which surfaces in whatever
                        // test is running as an opaque read timeout pointing at production code.
                    }
                }
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
     *
     * The old channel is closed **before** the replacement is opened: while both are live they are two
     * sockets aimed at one destination, so the kernel can hand the new one an ephemeral port that
     * recreates the old one's 4-tuple and fail the connect (see [openUpstream]). Releasing the tuple
     * first removes that collision instead of racing it. A rebind drops in-flight packets by
     * definition — QUIC recovers them by retransmit — so the extra sub-millisecond gap costs nothing.
     *
     * Blocks until the swap lands and **throws** if it didn't. A relay that silently fails to rebind
     * stops forwarding, which the caller can only observe as a read timeout attributed to the QUIC
     * driver; a harness fault must not be able to masquerade as a protocol failure.
     */
    fun rebindUpstream() {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        pending.add {
            try {
                val old = upstream
                old.keyFor(selector)?.cancel()
                closeQuietly(old)
                upstream = openUpstream()
            } catch (e: Exception) {
                failure.set(e)
            } finally {
                done.countDown()
            }
        }
        selector.wakeup()
        check(done.await(REBIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "relay upstream rebind did not complete within ${REBIND_TIMEOUT_MS}ms (pump thread alive=${pump.isAlive})"
        }
        failure.get()?.let { throw IllegalStateException("relay upstream rebind failed", it) }
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
        private const val REBIND_TIMEOUT_MS = 2000L

        /** Ephemeral-tuple collisions are rare and independent; a handful of attempts is plenty. */
        private const val OPEN_UPSTREAM_ATTEMPTS = 8
    }
}
