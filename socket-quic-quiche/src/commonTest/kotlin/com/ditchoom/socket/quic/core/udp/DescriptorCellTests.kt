package com.ditchoom.socket.quic.core.udp

import kotlin.test.Test
import kotlin.test.assertEquals

/** [DescriptorCell]: a descriptor is closed once, and a closed cell never touches the number it held again. */
class DescriptorCellTests {
    @Test
    fun aSecondCloseClosesNothing() {
        val cell = DescriptorCell(7)
        val closed = ArrayList<Int>()
        cell.close { closed.add(it) }
        cell.close { closed.add(it) }
        assertEquals(listOf(7), closed)
    }

    @Test
    fun aReplaceAfterTheCloseNeverReachesTheOldNumber() {
        val cell = DescriptorCell(7)
        cell.close { }
        var swapped = 0
        assertEquals(
            Quiesced.WasClosed,
            cell.replace {
                swapped++
                Swapped.To(9)
            },
        )
        assertEquals(0, swapped)
        assertEquals(Unit, cell.use({ error("a call on a closed cell") }, { Unit }, { Unit }))
    }

    @Test
    fun aCellClosedByAReplaceThatCouldNotReopenIsNotClosedAgain() {
        val cell = DescriptorCell(7)
        cell.replace { Swapped.Gone }
        var closes = 0
        cell.close { closes++ }
        assertEquals(0, closes)
    }
}
