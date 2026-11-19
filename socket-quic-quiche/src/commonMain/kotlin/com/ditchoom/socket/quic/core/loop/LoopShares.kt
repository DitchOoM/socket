package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.SessionScratch
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch

/** Datagrams a client loop collects for the network in one serve: the batch its connections share, one serve at a time. */
internal val SEND_LIMITS = BatchLimits(maxDatagrams = 64, maxDatagramSize = MAX_DATAGRAM)
internal val SEND_BYTES = SEND_LIMITS.maxDatagrams * SEND_LIMITS.maxDatagramSize

/**
 * Lanes for threads that send in place, shared by a client loop's connections. A thread that finds none free hands its
 * send to the loop, so the count bounds memory, not correctness. A lane is one of the pool's size classes, filled with
 * datagrams.
 */
internal const val LOOP_LANES = 8
internal const val LANE_BYTES = 16 * 1024
internal val LANE_LIMITS = BatchLimits(maxDatagrams = LANE_BYTES / MAX_DATAGRAM, maxDatagramSize = MAX_DATAGRAM)

/**
 * A spare batch for a loop's [LoopSend], from the factory its first connection brought. On the JDK stack a spare the
 * channel cannot write from is refused here, so every batch a JDK socket is handed has a view.
 */
internal fun spareOf(
    factory: BufferFactory,
    stack: NetStack,
): SpareMade {
    val buffer = factory.allocate(SEND_BYTES)
    val bytes =
        when (val made = NativeBytes.of(buffer)) {
            is Addressed.Native -> made.bytes
            is Addressed.NotAddressable -> {
                buffer.freeNativeMemory()
                return SpareMade.Refused
            }
        }
    when (stack) {
        is NetStack.Native -> Unit
        is NetStack.Jdk ->
            when (jdkViewsOf(stack, listOf(ReceiveBuffer(0, bytes)))) {
                is JdkViewsMade.Made -> Unit
                is JdkViewsMade.NotJdkMemory -> {
                    bytes.free()
                    return SpareMade.Refused
                }
            }
    }
    return SpareMade.Made(SendBatch(bytes, SEND_LIMITS))
}

/**
 * A loop on its thread, with what its sockets receive through and the memory its connections share: the batch the loop
 * collects their output into and the lanes threads that are not the loop send from.
 */
internal class RunningLoop(
    val loop: Loop,
    val io: LoopIo,
    val shared: LoopMemory,
    /** Held by the loop until it stops and by each connection until its memory is freed; frees [shared], [out] and [lanes]. */
    val hold: MemoryHold,
    /** How finely the loop's timed waits wake: what its connections pace within. */
    val waits: WaitResolution,
    val out: LoopSend,
    val lanes: FixedPool<CallerSend>,
    /** What its client connections copy a session ticket or token through. */
    val sessions: FixedPool<SessionScratch>,
)
