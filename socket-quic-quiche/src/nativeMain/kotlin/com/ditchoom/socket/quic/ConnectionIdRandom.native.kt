package com.ditchoom.socket.quic

import kotlin.concurrent.AtomicLong
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * [Random.Default]'s generator is not safe to draw from on several threads (see [connectionIdRandom]), so
 * native draws from a SplitMix64 sequence whose only state is one atomic counter: every draw claims its
 * own counter value with a single `addAndGet`, and the output is a bijective mix of it, so no two draws
 * ever return the same 64 bits however many threads race. No lock, and no thread waits on another.
 *
 * Seeded once from [Random.Default] and the clock, which is the only use of either; a seed that two
 * processes happen to share only makes them issue the same ids, which matters to nobody.
 */
internal class AtomicSplitMixRandom(
    seed: Long,
) : Random() {
    private val counter = AtomicLong(seed)

    override fun nextLong(): Long {
        var z = counter.addAndGet(GOLDEN_GAMMA)
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else (nextLong() ushr (64 - bitCount)).toInt()

    private companion object {
        const val GOLDEN_GAMMA = -7046029254386353131L // 0x9E3779B97F4A7C15
        const val MIX_1 = -4658895280553007687L // 0xBF58476D1CE4E5B9
        const val MIX_2 = -7723592293110705685L // 0x94D049BB133111EB
    }
}

private fun seed(): Long {
    val sinceStart = TimeSource.Monotonic.markNow().elapsedNow()
    return Random.Default.nextLong() xor sinceStart.inWholeNanoseconds
}

internal actual val connectionIdRandom: Random = AtomicSplitMixRandom(seed())
