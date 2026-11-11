package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A QUIC server's connections all send on one channel, and send options (TTL here) are socket-wide: applied by
 * `setsockopt`, then read by the kernel when the `sendmsg` runs. Two senders with different options must each
 * get their own on the wire, however their sends overlap.
 *
 * The first sender is parked after its options are in force and before its `sendmsg` is submitted; the second
 * then sends with different options. Whether the second may run meanwhile is the channel's choice; what it may
 * not do is change what the parked sender's datagram carries.
 */
@OptIn(ExperimentalDatagramApi::class)
class IoUringConcurrentSendersKeepTheirOptionsTests {
    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    @Test
    fun overlappingSendersEachCarryTheirOwnHopLimit() =
        runBlocking {
            withTimeout(30_000) {
                val firstParked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val arrivals = AtomicInt(0)
                val sender =
                    boundLoopbackIoUringChannel(
                        beforeSendSubmit = {
                            if (arrivals.incrementAndGet() == 1) {
                                firstParked.complete(Unit)
                                release.await()
                            }
                        },
                    )
                val receiver = boundLoopbackIoUringChannel()
                try {
                    val to = receiver.channel.localAddress
                    val a = async(Dispatchers.Default) { sender.channel.send(payload("A"), to, DatagramSendOptions(hopLimit = 7)) }
                    firstParked.await()
                    val b = async(Dispatchers.Default) { sender.channel.send(payload("B"), to, DatagramSendOptions(hopLimit = 9)) }
                    // B may finish while A is parked, or wait for it; either is correct.
                    withTimeoutOrNull(500) { b.await() }
                    release.complete(Unit)
                    a.await()
                    b.await()
                    val hopLimitOf = mutableMapOf<String, Int>()
                    repeat(2) {
                        val received = assertIs<DatagramReadResult.Received>(withTimeout(5_000) { receiver.channel.receive() })
                        val datagram = received.datagram
                        hopLimitOf[datagram.payload.readString(1)] = datagram.hopLimit.value
                    }
                    assertEquals(
                        mapOf("A" to 7, "B" to 9),
                        hopLimitOf,
                        "each overlapping sender's datagram must carry the hop limit that sender asked for",
                    )
                } finally {
                    sender.channel.close()
                    receiver.channel.close()
                }
            }
        }
}
