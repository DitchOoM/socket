package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** The library's ticket store: one use per ticket, newest wins, bounded in age and in servers, and lock-free. */
class SessionMemoryTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"))
    private val key = QuicSessionKey.of("example.test", 443, options)

    @Test
    fun aTicketIsTakenOnceAndTheNewestReplacesTheOlder() {
        val memory = QuicSessionMemory()
        memory.keep(key, ticket(1))
        memory.keep(key, ticket(2))
        assertEquals(QuicSessionTicketState.Issued(ticket(2)), memory.take(key))
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key), "a taken ticket is gone")
    }

    @Test
    fun aTicketHandedBackUnsentIsTakenAgainWithItsAge() {
        val time = TestTimeSource()
        val memory = QuicSessionMemory(time)
        val first = ticket(1)
        memory.keep(key, first)
        assertEquals(QuicSessionTicketState.Issued(first), memory.take(key))
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key), "a lent ticket is not taken twice")
        memory.restore(key, first)
        assertEquals(QuicSessionTicketState.Issued(first), memory.take(key), "the unsent ticket came back")
        memory.restore(key, first)
        time += QuicSessionMemory.MAX_AGE + 1.milliseconds
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key), "it came back as old as it was")
    }

    @Test
    fun aTicketHandedBackAfterANewerWasKeptIsDropped() {
        val memory = QuicSessionMemory()
        val first = ticket(1)
        memory.keep(key, first)
        memory.take(key)
        memory.keep(key, ticket(2))
        memory.restore(key, first)
        assertEquals(QuicSessionTicketState.Issued(ticket(2)), memory.take(key))
        memory.restore(key, ticket(2).let { QuicSessionTicket.restore(it.serialized(), it.protocol) })
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key), "only the very ticket lent goes back")
    }

    @Test
    fun aTicketPastItsAgeIsDroppedNotTaken() {
        val time = TestTimeSource()
        val memory = QuicSessionMemory(time)
        memory.keep(key, ticket(1))
        time += QuicSessionMemory.MAX_AGE + 1.milliseconds
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key))
        time += -QuicSessionMemory.MAX_AGE
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key), "the expired ticket was dropped")
    }

    @Test
    fun aKeyIsTheServerItsProtocolsAndHowItsCertificateIsChecked() {
        assertEquals(key, QuicSessionKey.of("example.test", 443, options.copy(idleTimeout = options.idleTimeout * 2)))
        assertNotEquals(key, QuicSessionKey.of("example.test", 8443, options))
        assertNotEquals(key, QuicSessionKey.of("other.test", 443, options))
        assertNotEquals(key, QuicSessionKey.of("example.test", 443, options.copy(alpnProtocols = listOf("h3"))))
        // Resuming skips the certificate: a ticket from an unchecked connection is not one a checked connection offers.
        assertNotEquals(key, QuicSessionKey.of("example.test", 443, options.copy(verifyPeer = false)))
        val memory = QuicSessionMemory()
        memory.keep(QuicSessionKey.of("example.test", 443, options.copy(verifyPeer = false)), ticket(1))
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(key))
    }

    @Test
    fun theLeastRecentlyKeptServerLeavesWhenTheMemoryIsFull() {
        val memory = QuicSessionMemory()
        val keys = List(QuicSessionMemory.SERVERS + 1) { QuicSessionKey.of("s$it.test", 443, options) }
        keys.forEachIndexed { i, k -> memory.keep(k, ticket(i)) }
        assertEquals(QuicSessionTicketState.NotIssued, memory.take(keys.first()))
        assertEquals(QuicSessionTicketState.Issued(ticket(keys.size - 1)), memory.take(keys.last()))
    }

    @Test
    fun ofManyThreadsTakingOneTicketExactlyOneGetsIt() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            repeat(ROUNDS) { round ->
                val memory = QuicSessionMemory()
                memory.keep(key, ticket(round))
                val start = CountDownLatch(1)
                val done = CountDownLatch(THREADS)
                val got = AtomicInteger()
                repeat(THREADS) {
                    pool.execute {
                        start.await()
                        if (memory.take(key) is QuicSessionTicketState.Issued) got.incrementAndGet()
                        // Keeps racing takes: a keep never resurrects a taken ticket.
                        memory.keep(QuicSessionKey.of("t$it.test", 443, options), ticket(it))
                        done.countDown()
                    }
                }
                start.countDown()
                done.await(10, TimeUnit.SECONDS)
                assertEquals(1, got.get(), "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ticket(n: Int): QuicSessionTicket {
        val bytes = BufferFactory.managed().allocate(4)
        bytes.writeInt(n)
        bytes.resetForRead()
        return QuicSessionTicket.restore(bytes, "test")
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 200
    }
}
