package com.ditchoom.socket.quic.sim

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.UdpChannel
import kotlinx.coroutines.channels.Channel

/**
 * In-memory [UdpChannel] driven by the [SimTimeline] interpreter — the packet-level seam of the W2
 * engine, replacing `StubUdpChannel`'s suspend-forever `receive()` so the driver can run
 * `clientMode = true` with its real UDP reader loop consuming scripted datagrams under virtual time.
 *
 * - [deliver] feeds one inbound datagram (hex payload) to the parked reader loop.
 * - [injectRecvError] surfaces a typed fault from the parked `receive()` — queued **in order** with
 *   datagrams, so a fixture can interleave data and faults deterministically.
 * - [injectSendError] arms a typed fault thrown from the **next** `send()`.
 * - Every successful `send()` is recorded into the [SimTrace] as [Observed.DatagramOut] — outbound
 *   datagrams are observations, not inputs (RFC_DETERMINISTIC_SIMULATION.md §2).
 */
internal class TimelineUdpChannel(
    private val trace: SimTrace,
) : UdpChannel {
    private sealed interface Delivery {
        class Data(
            val bytes: List<Byte>,
        ) : Delivery

        class Fail(
            val error: SimError,
        ) : Delivery
    }

    private val inbound = Channel<Delivery>(Channel.UNLIMITED)
    private var nextSendError: SimError? = null

    /** Queue one inbound datagram. [from] is accepted for event-model parity; single-path in W2. */
    fun deliver(
        payloadHex: String,
        @Suppress("UNUSED_PARAMETER") from: PathKey? = null,
    ) {
        val bytes = payloadHex.chunked(2).map { it.toInt(16).toByte() }
        require(bytes.isNotEmpty()) { "empty datagram payload — the reader loop drops 0-byte receives" }
        inbound.trySend(Delivery.Data(bytes))
    }

    /** Queue a typed fault the parked `receive()` will throw when it reaches this delivery. */
    fun injectRecvError(error: SimError) {
        inbound.trySend(Delivery.Fail(error))
    }

    /** Arm a typed fault thrown from the next `send()`. */
    fun injectSendError(error: SimError) {
        nextSendError = error
    }

    override suspend fun receive(buffer: PlatformBuffer): Int =
        when (val delivery = inbound.receive()) {
            is Delivery.Data -> {
                delivery.bytes.forEach { buffer.writeByte(it) }
                delivery.bytes.size
            }
            is Delivery.Fail -> throw SimIoException(delivery.error)
        }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        nextSendError?.let {
            nextSendError = null
            throw SimIoException(it)
        }
        trace.record(Observed.DatagramOut(trace.now(), len))
    }

    override fun close() {
        inbound.close()
    }
}
