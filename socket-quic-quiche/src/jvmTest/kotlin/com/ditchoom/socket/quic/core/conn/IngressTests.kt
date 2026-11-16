package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testLoopMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** A datagram that reaches the loop while a caller is inside is copied, never dropped while memory can be had. */
class IngressTests {
    private val q = QuicheConnRef(StubQuicheApi(), QuicheConn(1L))
    private val counts = ConnCounts()
    private val datagram = nativeBytes(16).also { for (i in 0 until 16) it.owner.set(i, i.toByte()) }

    @Test
    fun aCopyTakesAPooledSlotThenMemoryOfItsOwnAndCountsEach() {
        val shared = testLoopMemory(copies = 1)
        val slots = shared.slots
        val ingress = Ingress(q, shared, BufferFactory.Default, counts)

        val first = assertIs<CopiedWork>(ingress.copy(datagram, 16))
        assertEquals(16, slots[0].length)
        assertEquals(15, slots[0].bytes.byteAt(15))
        assertIs<CopiedWork>(ingress.copy(datagram, 16))
        assertEquals(2L, counts.datagramsCopied)

        first.gone(ConnEnd.Released)
        assertEquals(1, slots.available(), "a copy answered as gone gives its slot back")
    }

    @Test
    fun connectionsOnOneLoopShareItsCopySlots() {
        val shared = testLoopMemory(copies = 1)
        val one = Ingress(q, shared, BufferFactory.Default, counts)
        val other = Ingress(QuicheConnRef(StubQuicheApi(), QuicheConn(2L)), shared, BufferFactory.Default, ConnCounts())

        val held = assertIs<CopiedWork>(one.copy(datagram, 16))
        assertEquals(0, shared.slots.available(), "one connection's copy holds the loop's only slot")
        assertIs<CopiedWork>(other.copy(datagram, 16))
        assertEquals(0, shared.slots.available(), "the other connection's copy took memory of its own")
        held.gone(ConnEnd.Released)
        assertEquals(1, shared.slots.available())
    }

    @Test
    fun aFactoryWithoutNativeMemoryDropsTheDatagramAndCountsIt() {
        val ingress = Ingress(q, testLoopMemory(copies = 0), BufferFactory.managed(), counts)
        assertSame(Copy.Dropped, ingress.copy(datagram, 16))
        assertEquals(1L, counts.datagramsDropped)
    }
}
