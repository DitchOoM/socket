package com.ditchoom.socket.quic.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadChunkTests {
    @Test
    fun aReadThatFillsItsBufferDoublesTheNextUpToTheCeiling() {
        var chunk = ReadChunk.Initial
        val seen = mutableListOf(chunk.bytes)
        repeat(8) {
            chunk = chunk.after(asked = chunk.bytes, read = chunk.bytes)
            seen += chunk.bytes
        }
        assertEquals(listOf(2_048, 4_096, 8_192, 16_384, 32_768, 65_536, 65_536, 65_536, 65_536), seen)
    }

    @Test
    fun aReadThatUsesAQuarterOrLessHalvesTheNextDownToTheFloor() {
        var chunk = ReadChunk.Max
        val seen = mutableListOf(chunk.bytes)
        repeat(7) {
            chunk = chunk.after(asked = chunk.bytes, read = chunk.bytes / 4)
            seen += chunk.bytes
        }
        assertEquals(listOf(65_536, 32_768, 16_384, 8_192, 4_096, 2_048, 2_048, 2_048), seen)
    }

    @Test
    fun aReadBetweenAQuarterAndFullKeepsTheSize() {
        val chunk = ReadChunk.Max.after(asked = 65_536, read = 20_000)
        assertEquals(65_536, chunk.bytes)
    }
}
