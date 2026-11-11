package com.ditchoom.socket.quic

import kotlin.random.Random

/**
 * A [Random] whose every draw is fresh bytes from the operating system, with no state of ours: each draw
 * is its own system call, so any thread may draw and no draw depends on another. [nextBits] and
 * [nextInt] take the top bits of one [nextLong]. A failed read throws [EntropyUnavailableException].
 */
internal abstract class NativeEntropyRandom : Random() {
    override fun nextBits(bitCount: Int): Int = if (bitCount == 0) 0 else (nextLong() ushr (64 - bitCount)).toInt()
}
