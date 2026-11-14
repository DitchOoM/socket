package com.ditchoom.socket.udp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A connected UDP channel (a `NWConnection`) releases its descriptor when it is closed, whether or not a
 * receive is pending. The count is polled for up to [SETTLE] because Network.framework releases the
 * descriptor on its own queue; [SLACK] covers descriptors it opens lazily. A leak is one per cycle.
 */
class AppleUdpDescriptorReleaseTests {
    @Test
    fun aConnectedChannelClosedReleasesItsDescriptor() =
        runBlocking {
            val server = UdpSocket.bind("127.0.0.1", 0)
            try {
                assertReleased("udp connect, close") {
                    val client = UdpSocket.connect("127.0.0.1", server.localAddress.port)
                    client.close()
                }
            } finally {
                server.close()
            }
        }

    @Test
    fun aConnectedChannelClosedUnderAPendingReceiveReleasesItsDescriptor() =
        runBlocking {
            val server = UdpSocket.bind("127.0.0.1", 0)
            try {
                assertReleased("udp close under pending receive") {
                    val client = UdpSocket.connect("127.0.0.1", server.localAddress.port)
                    val receiving = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { client.receive() }
                    delay(2.milliseconds)
                    client.close()
                    receiving.await()
                }
            } finally {
                server.close()
            }
        }

    private suspend fun assertReleased(
        path: String,
        cycle: suspend () -> Unit,
    ) {
        repeat(WARM_UP) { cycle() }
        settle(openDescriptorCount())
        val baseline = openDescriptorCount()
        var peak = baseline
        repeat(CYCLES) {
            cycle()
            peak = maxOf(peak, openDescriptorCount())
        }
        val final = settle(baseline)
        println("DESCRIPTOR-TABLE $path | $CYCLES | $baseline | $peak | $final")
        assertTrue(final <= baseline + SLACK, "$path: $final descriptors open after $CYCLES cycles, baseline $baseline (peak $peak)")
    }

    private suspend fun settle(target: Int): Int {
        val start = TimeSource.Monotonic.markNow()
        var count = openDescriptorCount()
        while (count > target && start.elapsedNow() < SETTLE) {
            delay(50.milliseconds)
            count = openDescriptorCount()
        }
        return count
    }

    private companion object {
        const val CYCLES = 200
        const val WARM_UP = 3
        const val SLACK = 6
        val SETTLE = 10.seconds
    }
}
