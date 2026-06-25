package com.ditchoom.socket.http3.fuzz

import com.ditchoom.socket.http3.Http3FuzzGenerators
import kotlinx.coroutines.runBlocking

/**
 * Coverage-guided **Jazzer** fuzz target for the *round-trip* invariant — the encoder-side twin of
 * [Http3CodecFuzzer] (which fuzzes the decoder). The driver's `byte[]` is wrapped in a [ByteEntropy] and
 * fed to the shared [Http3FuzzGenerators], which build a structurally **valid** [com.ditchoom.socket.http3.Http3Frame]
 * or QPACK header list. Each is run through `encode → decode`; the assert oracles inside the generators
 * require the result to come back EQUAL.
 *
 * **Invariant** (the bug being hunted): a valid value always survives the round-trip. Unlike the decoder
 * fuzzer, NOTHING is tolerated — a mismatch throws (the generators' `assertEquals`), and so does any
 * stray error from encoding/decoding our own valid output; either bubbles to [fuzzerTestOneInput] and
 * Jazzer records a `crash-*` repro. The same bytes also drive the seeded `Http3RoundTripFuzzTests`, so a
 * repro is replayable on every platform.
 *
 * Run it via the `http3RoundTripFuzz` Gradle task. The target uses the `byte[]` entry-point form, so it
 * carries no compile-time Jazzer dependency. Intentionally NOT a `@Test`.
 */
object Http3RoundTripFuzzer {
    private const val INPUT_CAP = 4096

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val len = if (data.size > INPUT_CAP) INPUT_CAP else data.size
        if (len == 0) return
        // The whole dispatch + oracle lives in the shared, every-platform Http3FuzzGenerators.replay, so a
        // crash repro recorded here re-runs byte-identically in Http3RoundTripCorpusReplayTests on jvm/js/native.
        runBlocking { Http3FuzzGenerators.replay(data.copyOf(len)) }
    }
}
