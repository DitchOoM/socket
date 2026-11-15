package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun datagram(payload: PlatformBuffer): Datagram =
    Datagram(
        payload = payload,
        peer = SocketAddress.ofLiteral("192.0.2.1", 4433),
        ecn = Ecn.Unknown,
        localAddress = LocalAddress.Unknown,
        hopLimit = HopLimit.Unknown,
    )

class ServerDatagramIntakeTests {
    /** A socket holding [queued] datagrams: each `receive` returns one at once, and the next waits for ever. */
    private class QueuedSocket(
        private val queued: Int,
    ) {
        var received = 0
            private set

        suspend fun receive(): DatagramReadResult {
            if (received == queued) awaitCancellation()
            received++
            return DatagramReadResult.Received(datagram(BufferFactory.Default.allocate(1)))
        }
    }

    /** Takes what is held now, as the receive loop does in one pass, and frees it. */
    private fun ServerDatagramIntake.takeHeld(): Int {
        var taken = 0
        while (true) {
            when (val held = poll()) {
                is ServerDatagramIntake.Held.Next -> {
                    freeUndeliveredDatagram(held.result)
                    taken++
                }
                ServerDatagramIntake.Held.Nothing, ServerDatagramIntake.Held.ReaderEnded -> return taken
            }
        }
    }

    @Test
    fun everyDatagramTheSocketHoldsIsTakenOffItBeforeTheLoopTakesOne() =
        runTest {
            val socket = QueuedSocket(queued = 100)
            val intake = ServerDatagramIntake(socket::receive, isClosing = { false })
            val reader = launch { intake.read() }
            runCurrent()

            assertEquals(100, socket.received, "datagrams read off the socket while the loop has taken none")
            assertEquals(100, intake.takeHeld(), "datagrams one pass of the loop finds held")
            reader.cancel()
        }

    @Test
    fun theReaderStopsReadingWhenItHoldsItsCapacity() =
        runTest {
            val socket = QueuedSocket(queued = 100)
            val intake = ServerDatagramIntake(socket::receive, isClosing = { false }, capacity = 8)
            val reader = launch { intake.read() }
            runCurrent()

            // Eight held, and the ninth read and waiting to be held.
            assertEquals(9, socket.received, "datagrams read off the socket with the loop stalled")
            assertEquals(9, intake.takeHeld(), "the held datagrams and the one that was waiting")
            runCurrent()
            assertEquals(18, socket.received, "the reader resumes once the loop has taken what was held")
            intake.takeHeld()
            reader.cancel()
        }

    /** Counts its own release, which is all this test needs to see of a payload. */
    private class CountingBuffer(
        private val buffer: PlatformBuffer,
    ) : PlatformBuffer by buffer {
        var freed = 0
            private set

        override fun freeNativeMemory() {
            freed++
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun whatIsHeldWhenTheLoopStopsIsFreed() =
        runTest {
            val payloads = List(3) { CountingBuffer(BufferFactory.Default.allocate(1)) }
            val results = ArrayDeque(payloads.map { DatagramReadResult.Received(datagram(it)) })
            val intake =
                ServerDatagramIntake({ if (results.isEmpty()) awaitCancellation() else results.removeFirst() }, isClosing = { false })
            val reader = launch { intake.read() }
            runCurrent()

            reader.cancel()
            intake.freeHeld()

            assertEquals(listOf(1, 1, 1), payloads.map { it.freed }, "times each held payload was freed")
        }

    @Test
    fun aClosedSocketEndsTheReaderAfterWhatItHeldIsTaken() =
        runTest {
            val results = ArrayDeque<DatagramReadResult>(listOf(DatagramReadResult.Closed()))
            val intake = ServerDatagramIntake({ results.removeFirst() }, isClosing = { false })
            launch { intake.read() }
            runCurrent()

            assertIs<DatagramReadResult.Closed>(assertIs<ServerDatagramIntake.Held.Next>(intake.poll()).result)
            assertEquals(ServerDatagramIntake.Held.ReaderEnded, intake.poll())
        }
}
