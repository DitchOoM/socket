package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** The JVM/Android half of [ConnectionIdRandomTests]: the production default draws from `SecureRandom`. */
class ConnectionIdRandomJvmTests {
    @Test
    fun theProductionDefaultIsSecureRandom() {
        assertIs<SecureJvmRandom>(connectionIdRandom)
        assertIs<SecureJvmRandom>(QuicheDriverTuning().random)
    }

    @Test
    fun twoInstancesDoNotShareTheirDraws() {
        val a = SecureJvmRandom()
        val b = SecureJvmRandom()
        val drawsA = (0 until 4).map { a.nextLong() }
        val drawsB = (0 until 4).map { b.nextLong() }
        assertNotEquals(drawsA, drawsB, "two instances produced the same first draws")
        assertEquals(8, (drawsA + drawsB).toSet().size, "a draw repeated an earlier one")
    }
}
