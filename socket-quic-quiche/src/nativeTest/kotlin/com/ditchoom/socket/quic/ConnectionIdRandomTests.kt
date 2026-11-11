package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The source every connection id is drawn from is shared by the server's receive loop (the SCID of each
 * connection it accepts) and by every established connection's driver (its spare ids), which run on
 * different threads. If two draws can return the same bytes, two connections own one id: the server
 * routes it to whichever claimed it last and the other connection's datagrams go to a driver that
 * cannot read them, so its handshake never completes. This is the defect behind the nightly soak's
 * `awaiting-echo=1 ... server[accepted=23]` stall, where one of 24 concurrent connections never got a
 * handler.
 */
class ConnectionIdRandomTests {
    @Test
    fun threadsDrawingConnectionIdsConcurrentlyNeverDrawTheSameId() {
        val random = QuicheDriverTuning().random
        val workers = 16
        val drawsPerWorker = 50_000
        val drawn =
            runBlocking {
                (0 until workers)
                    .map {
                        async(Dispatchers.Default) {
                            // Two longs, exactly what generateScid() writes: a repeat needs both to repeat.
                            LongArray(drawsPerWorker * 2) { random.nextLong() }
                        }
                    }.awaitAll()
            }
        val seen = HashSet<Pair<Long, Long>>(workers * drawsPerWorker)
        var repeats = 0
        for (worker in drawn) {
            for (i in 0 until drawsPerWorker) if (!seen.add(worker[2 * i] to worker[2 * i + 1])) repeats++
        }
        assertEquals(0, repeats, "${workers * drawsPerWorker} connection ids drawn on $workers threads: $repeats repeated an earlier one")
    }
}
