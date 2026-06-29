package com.ditchoom.socket.quic

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared (JVM + Android) [ImpairingProxy] for [QuicImpairmentTestSuite]. Lives in `sharedJvmTestProtocol`
 * so the JVM ([QuicImpairmentTests]) and Android ([AndroidQuicImpairmentTests]) members drive the exact
 * same impairment mechanics instead of two hand-kept copies.
 *
 * Datagram I/O runs on a shared non-blocking [SelectorDatagramRelay]; each direction applies [policy] per
 * datagram once [arm]ed. Delayed ([ImpairAction.ForwardAfter]) and held ([ImpairAction.HoldUntilNext])
 * datagrams are copied out of the (reused) recv buffer before the next receive overwrites it; duplicates
 * re-send the live buffer (both sends complete before the next receive). `ByteBuffer`/`ByteArray` are fine
 * here — test-only.
 */
internal class DatagramChannelImpairingProxy(
    serverPort: Int,
    private val policy: ImpairmentPolicy,
) : ImpairingProxy {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "impair-sched").apply { isDaemon = true } }

    @Volatile private var armed = false

    private val dropped = AtomicInteger(0)
    private val duplicated = AtomicInteger(0)
    private val delayed = AtomicInteger(0)
    private val reordered = AtomicInteger(0)

    override val droppedCount get() = dropped.get()
    override val duplicatedCount get() = duplicated.get()
    override val delayedCount get() = delayed.get()
    override val reorderedCount get() = reordered.get()

    override fun arm() {
        armed = true
    }

    /** Per-direction state + mechanics. [emitLive] sends the (positioned) recv buffer; [emitBytes] sends a copy. */
    private inner class DirectionPump(
        private val direction: ImpairDirection,
        private val emitLive: (ByteBuffer) -> Unit,
        private val emitBytes: (ByteArray) -> Unit,
    ) {
        private val index = AtomicInteger(0)

        @Volatile private var held: ByteArray? = null

        fun handle(
            buf: ByteBuffer,
            n: Int,
        ) {
            if (!armed) {
                emitLive(buf)
                return
            }
            val toRelease = held
            held = null
            when (val action = policy.actionFor(direction, index.getAndIncrement())) {
                ImpairAction.Forward -> emitLive(buf)
                ImpairAction.Drop -> dropped.incrementAndGet()
                ImpairAction.ForwardTwice -> {
                    emitLive(buf)
                    buf.rewind()
                    emitLive(buf)
                    duplicated.incrementAndGet()
                }
                is ImpairAction.ForwardAfter -> {
                    val copy = buf.toBytes(n)
                    // The relay's selector thread can still deliver a packet while close() is tearing the
                    // scheduler down (close() calls scheduler.shutdownNow() and the relay loop may process
                    // one more datagram before it stops). Scheduling onto an already-shutdown executor throws
                    // RejectedExecutionException, which would crash the relay thread and fail the test on a
                    // teardown race (the macOS/Android CI flake fixed in 66bec0a). A delayed packet
                    // mid-teardown is moot — the connection is closing — so drop it instead.
                    try {
                        scheduler.schedule({ emitBytes(copy) }, action.delayMs, TimeUnit.MILLISECONDS)
                        delayed.incrementAndGet()
                    } catch (_: RejectedExecutionException) {
                        dropped.incrementAndGet()
                    }
                }
                ImpairAction.HoldUntilNext -> {
                    held = buf.toBytes(n)
                    reordered.incrementAndGet()
                }
            }
            // Release the previously-held datagram AFTER the current one — the structural reorder.
            if (toRelease != null) emitBytes(toRelease)
        }

        private fun ByteBuffer.toBytes(n: Int): ByteArray {
            val a = ByteArray(n)
            duplicate().get(a)
            return a
        }
    }

    private val c2sPump =
        DirectionPump(ImpairDirection.ClientToServer, { relay.writeToServer(it) }, { relay.writeToServerBytes(it) })
    private val s2cPump =
        DirectionPump(ImpairDirection.ServerToClient, { relay.writeToClient(it) }, { relay.writeToClientBytes(it) })

    // The relay owns all DatagramChannel I/O on a single non-blocking Selector loop, making the close-time
    // `IOException: Success` teardown race structurally impossible (see its KDoc). Explicitly typed lateinit
    // + init{} construction: the pump callbacks reference the relay and the relay's ctor references the
    // pumps, so an inferred `val` would form a type-inference cycle.
    private lateinit var relay: SelectorDatagramRelay

    override val proxyPort: Int get() = relay.proxyPort

    init {
        relay =
            SelectorDatagramRelay(
                serverPort = serverPort,
                maxDatagram = MAX_DATAGRAM,
                onClientToServer = { buf, n -> c2sPump.handle(buf, n) },
                onServerToClient = { buf, n -> s2cPump.handle(buf, n) },
            )
        relay.start()
    }

    override suspend fun close() {
        scheduler.shutdownNow()
        relay.close()
    }

    companion object {
        private const val MAX_DATAGRAM = 2048
    }
}
