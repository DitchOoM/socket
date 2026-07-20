@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.testkit.fault.FaultSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * TCP Tier-A conformance-under-fault (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §2/§3, P3). Drives byte-runs
 * through the in-memory [ImpairedStreamPipe] under `runTest` virtual time and asserts each
 * [FaultSchedule]'s semantics land exactly — the deterministic, docker-free tier that runs on every
 * KMP target, the byte-stream twin of `UdpTierASimTests`.
 */
class TcpTierASimTests {
    private fun payload(text: String): ReadBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private suspend fun ByteStream.readBytes(): ByteArray {
        val buffer = (read(Duration.INFINITE) as ReadResult.Data).buffer
        return buffer.readByteArray(buffer.remaining())
    }

    private suspend fun ByteStream.readText(): String = readBytes().decodeToString()

    @Test
    fun clean_roundTripsEveryByteRunInOrderVerbatim() =
        runTest {
            val pipe = ImpairedStreamPipe(FaultSchedule.CLEAN, scope = this)
            repeat(4) { pipe.clientEndpoint.write(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.readText() }
            assertEquals(listOf("m0", "m1", "m2", "m3"), got)
            assertEquals(0, pipe.clientToServerStats.dropped)
            pipe.close()
        }

    @Test
    fun dropNth_omitsExactlyThatByteRun() =
        runTest {
            val pipe = ImpairedStreamPipe(FaultSchedule { drop(nth = 2) }, scope = this)
            repeat(5) { pipe.clientEndpoint.write(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.readText() }
            assertEquals(listOf("m0", "m1", "m3", "m4"), got)
            assertEquals(1, pipe.clientToServerStats.dropped)
            pipe.close()
        }

    @Test
    fun blackholeFrom_dropsTheEntireTail() =
        runTest {
            val pipe = ImpairedStreamPipe(FaultSchedule { blackholeFrom(nth = 3) }, scope = this)
            repeat(6) { pipe.clientEndpoint.write(payload("m$it")) }
            advanceUntilIdle()
            val got = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.readText() }
            assertEquals(listOf("m0", "m1", "m2"), got)
            assertEquals(3, pipe.clientToServerStats.dropped)
            pipe.close()
        }

    @Test
    fun delay_holdsTheByteRunUntilVirtualTimeAdvances() =
        runTest {
            val pipe = ImpairedStreamPipe(FaultSchedule { delay(50.milliseconds) }, scope = this)
            pipe.clientEndpoint.write(payload("late"))
            advanceUntilIdle()
            assertEquals(50L, testScheduler.currentTime, "delivery must be held exactly 50ms of virtual time")
            assertEquals("late", pipe.serverEndpoint.readText())
            pipe.close()
        }

    @Test
    fun corrupt_flipsExactlyTheTargetedByte() =
        runTest {
            val pipe =
                ImpairedStreamPipe(FaultSchedule { corrupt(nth = 0, offset = 0, flipMask = 0xFF) }, scope = this)
            pipe.clientEndpoint.write(payload("AAAA")) // 0x41 x4
            pipe.clientEndpoint.write(payload("AAAA"))
            advanceUntilIdle()
            val first = pipe.serverEndpoint.readBytes()
            val second = pipe.serverEndpoint.readBytes()
            // 0x41 xor 0xFF = 0xBE at offset 0; the rest of the run and the next run are untouched.
            assertContentEquals(byteArrayOf(0xBE.toByte(), 0x41, 0x41, 0x41), first)
            assertContentEquals(byteArrayOf(0x41, 0x41, 0x41, 0x41), second)
            pipe.close()
        }

    @Test
    fun directions_areImpairedIndependently() =
        runTest {
            val pipe =
                ImpairedStreamPipe(
                    clientToServer = FaultSchedule { drop(nth = 0) },
                    serverToClient = FaultSchedule.CLEAN,
                    scope = this,
                )
            repeat(2) { pipe.clientEndpoint.write(payload("c$it")) }
            repeat(2) { pipe.serverEndpoint.write(payload("s$it")) }
            advanceUntilIdle()
            val atServer = (0 until pipe.clientToServerStats.delivered).map { pipe.serverEndpoint.readText() }
            val atClient = (0 until pipe.serverToClientStats.delivered).map { pipe.clientEndpoint.readText() }
            assertEquals(listOf("c1"), atServer, "client→server drops the 0th")
            assertEquals(listOf("s0", "s1"), atClient, "server→client is clean")
            pipe.close()
        }

    @Test
    fun resetAfter_deliversThePrefixThenResetsBothDirections() =
        runTest {
            val pipe = ImpairedStreamPipe(FaultSchedule { resetAfter(units = 2) }, scope = this)
            pipe.clientEndpoint.write(payload("a"))
            pipe.clientEndpoint.write(payload("b"))
            // The connection is torn down once 2 units have crossed; the 3rd write hits the RST.
            assertFailsWith<SocketClosedException.ConnectionReset> { pipe.clientEndpoint.write(payload("c")) }
            advanceUntilIdle()
            assertTrue(pipe.wasReset)
            // The server drains the two delivered runs, then observes the reset.
            assertEquals("a", pipe.serverEndpoint.readText())
            assertEquals("b", pipe.serverEndpoint.readText())
            assertEquals(ReadResult.Reset, pipe.serverEndpoint.read(Duration.INFINITE))
            // The (clean, empty) reverse direction is torn down too — a RST kills the whole connection.
            assertEquals(ReadResult.Reset, pipe.clientEndpoint.read(Duration.INFINITE))
            pipe.close()
        }

    @Test
    fun sameSchedule_reproducesTheExactReceivedSequence() =
        runTest {
            val testScope = this

            fun schedule() =
                FaultSchedule(seed = 11) {
                    drop(nth = 3)
                    dropEvery(n = 4, offset = 1)
                    delay(5.milliseconds)
                }

            suspend fun once(): List<String> {
                val pipe = ImpairedStreamPipe(schedule(), scope = testScope)
                repeat(8) { pipe.clientEndpoint.write(payload("m$it")) }
                testScope.advanceUntilIdle()
                return (0 until pipe.clientToServerStats.delivered)
                    .map { pipe.serverEndpoint.readText() }
                    .also { pipe.close() }
            }
            assertEquals(once(), once())
        }

    @Test
    fun reorder_isRejected_tcpNeverReorders() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                ImpairedStreamPipe(FaultSchedule { reorder(window = 2) }, scope = this)
            }
        }

    @Test
    fun duplicate_isRejected_byteRunDuplicateIsNotExpressibleOnTheWire() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                ImpairedStreamPipe(FaultSchedule { duplicate(nth = 1) }, scope = this)
            }
        }
}
