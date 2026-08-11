package com.ditchoom.socket.quic

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.AtomicInt

/**
 * Apple K/Native member of [QuicImpairmentTestSuite] — the Apple counterpart of [QuicImpairmentTests]
 * (JVM), [LinuxQuicImpairmentTests] and `AndroidQuicImpairmentTests` (issue #296).
 *
 * Drives deterministic loss / reordering / duplication / latency+jitter / burst-blackhole on the Apple
 * QUIC datapath and asserts an 8 KB payload still round-trips byte-for-byte — i.e. quiche's
 * retransmit / ACK / loss-recovery / reassembly / dedup logic works over the `NWConnection` UDP client
 * egress and the dual-stack POSIX server. The suite's anti-vacuous `streamStallsUnderTotalBlackhole`
 * also proves the proxy really is the sole path on Darwin.
 *
 * The proxy is the Darwin twin of Linux's io_uring one, built on [ApplePosixUdpForwarder]. It differs
 * from Linux's only in memory handling: the base hands each datagram over as a `ByteArray` copy, so the
 * held (`HoldUntilNext`) and delayed (`ForwardAfter`) datagrams need no manual native-memory copy or
 * free — the exact bookkeeping the Linux version has to do by hand around its reused recv buffer.
 */
class AppleQuicImpairmentTests : QuicImpairmentTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy = PosixImpairingProxy(serverPort, policy)

    private class PosixImpairingProxy(
        serverPort: Int,
        private val policy: ImpairmentPolicy,
    ) : ApplePosixUdpForwarder(serverPort),
        ImpairingProxy {
        private val armedFlag = AtomicInt(0)
        private val dropped = AtomicInt(0)
        private val duplicated = AtomicInt(0)
        private val delayed = AtomicInt(0)
        private val reordered = AtomicInt(0)

        private val c2sPump = DirectionPump(ImpairDirection.ClientToServer) { forwardUpstream(it) }
        private val s2cPump = DirectionPump(ImpairDirection.ServerToClient) { forwardToClient(it) }

        init {
            start()
        }

        override val proxyPort: Int get() = boundPort
        override val droppedCount get() = dropped.value
        override val duplicatedCount get() = duplicated.value
        override val delayedCount get() = delayed.value
        override val reorderedCount get() = reordered.value

        override fun arm() {
            armedFlag.value = 1
        }

        override suspend fun onClientToServer(datagram: ByteArray) = c2sPump.handle(datagram)

        override suspend fun onServerToClient(datagram: ByteArray) = s2cPump.handle(datagram)

        override suspend fun close() = shutdown()

        /**
         * Per-direction index, held-datagram slot and the policy mechanics. Confined to its own
         * direction's pump thread, so [index] and [held] need no synchronisation; only the counters
         * (read by the test thread's assertions) are atomic.
         */
        private inner class DirectionPump(
            private val direction: ImpairDirection,
            private val send: (ByteArray) -> Unit,
        ) {
            private var index = 0
            private var held: ByteArray? = null

            fun handle(datagram: ByteArray) {
                if (armedFlag.value == 0) {
                    send(datagram)
                    return
                }
                val toRelease = held
                held = null
                when (val action = policy.actionFor(direction, index++)) {
                    ImpairAction.Forward -> send(datagram)
                    ImpairAction.Drop -> dropped.incrementAndGet()
                    ImpairAction.ForwardTwice -> {
                        send(datagram)
                        send(datagram)
                        duplicated.incrementAndGet()
                    }
                    is ImpairAction.ForwardAfter -> {
                        scope.launch {
                            delay(action.delayMs)
                            send(datagram)
                        }
                        delayed.incrementAndGet()
                    }
                    ImpairAction.HoldUntilNext -> {
                        held = datagram
                        reordered.incrementAndGet()
                    }
                }
                // Release the previously-held datagram AFTER the current one — the structural reorder.
                if (toRelease != null) send(toRelease)
            }
        }
    }
}
