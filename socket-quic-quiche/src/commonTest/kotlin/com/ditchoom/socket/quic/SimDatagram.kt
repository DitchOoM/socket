package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * One datagram in flight inside a sim pipe, held in native memory the pipe owns.
 *
 * The sim pipes stand in for the wire, and a wire carries bytes the sender no longer owns — so a
 * datagram offered to a pipe is *copied*. What it is copied **into** is the point of this type: a
 * [PlatformBuffer] from the same network factory the driver uses, never a `ByteArray`. The pipes used
 * to marshal every datagram out to the heap and back (`ByteBuffer` → `ByteArray` → `ByteBuffer`),
 * which cost two copies and an allocation per datagram and — the reason this type exists — put the
 * `[0, len)` window question at three separate call sites, where getting it wrong at any one of them
 * stalls every handshake in the suite.
 *
 * Now there is exactly one place that reads a written datagram out of a buffer ([DatagramLedger.capture])
 * and exactly one that offers it back for reading ([readable]), so the window is stated once.
 */
internal class PipeDatagram(
    val buffer: PlatformBuffer,
    val length: Int,
    /** Where this datagram entered the pipe, so an imbalance names its own origin. */
    val origin: String,
) {
    /**
     * This datagram's bytes, positioned for reading.
     *
     * ⚠️ **Never `resetForRead()`.** These buffers are filled through their native address (quiche's
     * `send`, or a [DatagramLedger.capture] copy) and the position is not moved by that write, so the
     * flip `resetForRead()` performs yields `limit = 0` — an empty window, and a datagram that reads
     * as zero bytes. The readable window here is always absolute: `[0, length)`.
     */
    fun readable(): PlatformBuffer {
        buffer.position(0)
        buffer.setLimit(length)
        return buffer
    }

    /** The first byte, for classifying a packet type without reading the datagram out. */
    fun firstByte(): Byte {
        buffer.position(0)
        buffer.setLimit(length)
        return buffer.readByte()
    }
}

/**
 * Allocator and accounting for the native buffers a sim pipe owns.
 *
 * Carrying [PipeDatagram]s instead of `ByteArray`s buys one copy and zero heap garbage per datagram,
 * and costs a **free obligation**: a `ByteArray` is collected on every platform, whereas these buffers
 * are `malloc`'d on Kotlin/Native and leak unless somebody frees them. Every datagram this ledger
 * hands out therefore ends in exactly one of two places — [release] (the pipe freed it after copying
 * it into the receiver's buffer, or while draining a closed pipe) or [transfer] (ownership passed to
 * the driver, which frees it after `quiche_conn_recv`).
 *
 * [outstanding] is what makes that checkable rather than hoped for: a harness built to find leaks and
 * use-after-frees must not have its own, so the sim asserts this is zero once a scenario ends. A
 * datagram that is neither delivered, dropped, drained nor transferred shows up as a failing test
 * instead of as native memory nobody notices.
 *
 * A dropped or blackholed datagram is **never captured at all** — the decision happens first — so the
 * ledger counts real deliveries and the seeded decision sequence stays independent of allocation.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DatagramLedger(
    private val bufferFactory: BufferFactory,
) {
    private val captured = AtomicInt(0)
    private val released = AtomicInt(0)
    private val transferred = AtomicInt(0)

    private val lock = SynchronizedObject()
    private val outstandingByOrigin = mutableMapOf<String, Int>()

    private fun account(datagram: PipeDatagram) {
        synchronized(lock) {
            val left = (outstandingByOrigin[datagram.origin] ?: 0) - 1
            if (left == 0) outstandingByOrigin.remove(datagram.origin) else outstandingByOrigin[datagram.origin] = left
        }
    }

    /**
     * Copy the `[0, length)` bytes [source] holds into a pipe-owned buffer.
     *
     * [source] is a driver buffer quiche has just written through its native address; the write does
     * not move the position, so the readable window is set absolutely here rather than flipped. This
     * is the only place in the sim that reads a datagram out of a driver buffer.
     */
    fun capture(
        source: PlatformBuffer,
        length: Int,
        origin: String,
    ): PipeDatagram {
        require(length >= 0) { "a datagram cannot have negative length: $length" }
        val copy = bufferFactory.allocate(length)
        source.position(0)
        source.setLimit(length)
        copy.write(source)
        captured.incrementAndFetch()
        synchronized(lock) { outstandingByOrigin[origin] = (outstandingByOrigin[origin] ?: 0) + 1 }
        return PipeDatagram(copy, length, origin)
    }

    /** Free a datagram the pipe still owns — after delivering it, or while draining a closed pipe. */
    fun release(datagram: PipeDatagram) {
        datagram.buffer.freeNativeMemory()
        released.incrementAndFetch()
        account(datagram)
    }

    /**
     * Give up ownership of a datagram handed to a driver. `QuicheCmd.RecvPacket` frees its buffer once
     * the command is executed or failed, so the pipe must not also free it — but it is no longer
     * outstanding either.
     */
    fun transfer(datagram: PipeDatagram) {
        transferred.incrementAndFetch()
        account(datagram)
    }

    /** Datagrams captured and neither freed nor handed to a driver. Zero at the end of a clean run. */
    fun outstanding(): Int = captured.load() - released.load() - transferred.load()

    /**
     * Diagnostic breakdown for an assertion message, including WHICH capture site is unbalanced — a
     * leak that names its own origin is one nobody has to bisect for.
     */
    fun summary(): String =
        "captured=${captured.load()} released=${released.load()} transferred=${transferred.load()}" +
            synchronized(lock) { if (outstandingByOrigin.isEmpty()) "" else " unbalanced=$outstandingByOrigin" }
}

/**
 * The first byte of the `[0, len)` datagram [buffer] holds, read without consuming it — enough to
 * classify a QUIC packet type (RFC 9000 §17.2) for a diagnostic trace. Uses the same absolute window
 * as [DatagramLedger.capture]; see [PipeDatagram.readable] for why it is never a `resetForRead()`.
 */
internal fun firstByteOf(
    buffer: PlatformBuffer,
    len: Int,
): Byte {
    if (len <= 0) return 0
    buffer.position(0)
    buffer.setLimit(len)
    return buffer.readByte()
}
