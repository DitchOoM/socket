@file:OptIn(ExperimentalDatagramApi::class, ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.udp.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.testkit.fault.FaultSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * UDP Tier-A conformance-under-fault (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §2, P1). Drives datagrams
 * through the in-memory [ImpairedDatagramPipe] under `runTest` virtual time and asserts each
 * [FaultSchedule]'s semantics land exactly — the deterministic, docker-free tier that runs on every
 * KMP target.
 */
class UdpTierASimTests {
    private fun payload(text: String): ReadBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private suspend fun DatagramChannel.recvBytes(): ByteArray {
        val datagram = (receive() as DatagramReadResult.Received).datagram
        return datagram.payload.readByteArray(datagram.payload.remaining())
    }

    private suspend fun DatagramChannel.recvText(): String = recvBytes().decodeToString()

    @Test
    fun clean_roundTripsEveryDatagramInOrderVerbatim() =
        runTest {
            val pipe = ImpairedDatagramPipe(FaultSchedule.CLEAN, scope = this)
            repeat(4) { pipe.clientEndpoint.send(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.recvText() }
            assertEquals(listOf("m0", "m1", "m2", "m3"), got)
            assertEquals(0, pipe.clientToServerStats.dropped)
            pipe.close()
        }

    @Test
    fun dropNth_omitsExactlyThatDatagram() =
        runTest {
            val pipe = ImpairedDatagramPipe(FaultSchedule { drop(nth = 2) }, scope = this)
            repeat(5) { pipe.clientEndpoint.send(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.recvText() }
            assertEquals(listOf("m0", "m1", "m3", "m4"), got)
            assertEquals(1, pipe.clientToServerStats.dropped)
            pipe.close()
        }

    @Test
    fun blackholeFrom_dropsTheEntireTail() =
        runTest {
            val pipe = ImpairedDatagramPipe(FaultSchedule { blackholeFrom(nth = 3) }, scope = this)
            repeat(6) { pipe.clientEndpoint.send(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.recvText() }
            assertEquals(listOf("m0", "m1", "m2"), got)
            assertEquals(3, pipe.clientToServerStats.dropped)
            pipe.close()
        }

    @Test
    fun delay_holdsTheDatagramUntilVirtualTimeAdvances() =
        runTest {
            val pipe = ImpairedDatagramPipe(FaultSchedule { delay(50.milliseconds) }, scope = this)
            pipe.clientEndpoint.send(payload("late"))
            advanceUntilIdle()
            assertEquals(50L, testScheduler.currentTime, "delivery must be held exactly 50ms of virtual time")
            assertEquals("late", pipe.serverEndpoint.recvText())
            pipe.close()
        }

    @Test
    fun duplicate_deliversTwoCopies_theDuplicateTrailing() =
        runTest {
            val pipe = ImpairedDatagramPipe(FaultSchedule { duplicate(nth = 1) }, scope = this)
            repeat(3) { pipe.clientEndpoint.send(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.recvText() }
            // m0,m1,m2 enqueue at t0 in order; the m1 duplicate trails by 1ms, arriving last.
            assertEquals(listOf("m0", "m1", "m2", "m1"), got)
            pipe.close()
        }

    @Test
    fun corrupt_flipsExactlyTheTargetedByte() =
        runTest {
            val pipe =
                ImpairedDatagramPipe(FaultSchedule { corrupt(nth = 0, offset = 0, flipMask = 0xFF) }, scope = this)
            pipe.clientEndpoint.send(payload("AAAA")) // 0x41 x4
            pipe.clientEndpoint.send(payload("AAAA"))
            advanceUntilIdle()
            val first = pipe.serverEndpoint.recvBytes()
            val second = pipe.serverEndpoint.recvBytes()
            // 0x41 xor 0xFF = 0xBE at offset 0; the rest and the next datagram are untouched.
            assertContentEquals(byteArrayOf(0xBE.toByte(), 0x41, 0x41, 0x41), first)
            assertContentEquals(byteArrayOf(0x41, 0x41, 0x41, 0x41), second)
            pipe.close()
        }

    @Test
    fun directions_areImpairedIndependently() =
        runTest {
            val pipe =
                ImpairedDatagramPipe(
                    clientToServer = FaultSchedule { drop(nth = 0) },
                    serverToClient = FaultSchedule.CLEAN,
                    scope = this,
                )
            repeat(2) { pipe.clientEndpoint.send(payload("c$it")) }
            repeat(2) { pipe.serverEndpoint.send(payload("s$it")) }
            advanceUntilIdle()
            val atServer = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.recvText() }
            val atClient = (0 until pipe.serverToClientStats.delivered).map { pipe.clientEndpoint.recvText() }
            assertEquals(listOf("c1"), atServer, "client→server drops the 0th")
            assertEquals(listOf("s0", "s1"), atClient, "server→client is clean")
            pipe.close()
        }

    @Test
    fun sameSchedule_reproducesTheExactReceivedSequence() =
        runTest {
            val testScope = this

            fun schedule() =
                FaultSchedule(seed = 11) {
                    drop(nth = 3)
                    duplicate(nth = 1)
                    reorder(window = 2)
                }

            suspend fun once(): List<String> {
                val pipe = ImpairedDatagramPipe(schedule(), scope = testScope)
                repeat(8) { pipe.clientEndpoint.send(payload("m$it")) }
                testScope.advanceUntilIdle()
                return (0 until pipe.clientToServerStats.delivered)
                    .map { pipe.serverEndpoint.recvText() }
                    .also { pipe.close() }
            }
            assertEquals(once(), once())
        }

    @Test
    fun resetAfter_isRejected_udpHasNoConnectionToReset() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                ImpairedDatagramPipe(FaultSchedule { resetAfter(units = 2) }, scope = this)
            }
        }
}
