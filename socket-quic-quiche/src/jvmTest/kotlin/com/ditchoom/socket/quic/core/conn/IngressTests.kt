package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.testing.nativeBytes
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
        val slots = FixedPool(1) { CopySlot(it, nativeBytes(1500)) }
        val ingress = Ingress(q, slots, BufferFactory.Default, counts)

        val first = assertIs<CopiedWork>(ingress.copy(datagram, 16))
        assertEquals(16, slots[0].length)
        assertEquals(15, slots[0].bytes.byteAt(15))
        assertIs<CopiedWork>(ingress.copy(datagram, 16))
        assertEquals(2L, counts.datagramsCopied)

        first.gone(ConnEnd.Released)
        assertEquals(1, slots.available(), "a copy answered as gone gives its slot back")
    }

    @Test
    fun aFactoryWithoutNativeMemoryDropsTheDatagramAndCountsIt() {
        val ingress = Ingress(q, FixedPool(0) { CopySlot(it, nativeBytes(1500)) }, BufferFactory.managed(), counts)
        assertSame(Copy.Dropped, ingress.copy(datagram, 16))
        assertEquals(1L, counts.datagramsDropped)
    }
}
