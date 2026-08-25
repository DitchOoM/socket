package com.ditchoom.socket.testkit.echo

import kotlin.text.CharacterCodingException

/**
 * Evidence capture for the echo-corruption family (#401, #366, #415, #462).
 *
 * ## Why this is shared rather than local to one suite
 *
 * An echo test that decodes as it reads dies inside the decoder with a bare
 * `MalformedInputException: Input length = 1` and **discards the bytes that would explain it**. #402
 * fixed that for one suite — `QuicConcurrencySoakTestSuite` — by reading all the bytes first and
 * decoding last, which is what unblocked #291/#292. It stayed private to that class, so every other
 * echo site kept throwing the evidence away.
 *
 * That cost a release. `StaleConnectionDiagnosticTests` failed on the v4.10.0 release run
 * (`build-linux / QUIC quiche JVM (JNI)`, 1 of 344 tests) and the artifact contained a stack trace and
 * **zero characters** of captured output, because the decode happened inside the assertion path.
 *
 * The rule this encodes is worth stating on its own:
 *
 * > An echo assertion never decodes inside the assertion path. Compare raw bytes; decode only to build
 * > the failure message.
 *
 * ## Why it classifies rather than only dumping
 *
 * Three explanations fit every sighting so far, and they have different root causes and different
 * fixes. A hex dump alone does not choose between them; the *shape* of the received bytes does:
 *
 * | received bytes look like | reading |
 * |---|---|
 * | another payload that was in flight | cross-connection or cross-stream leak |
 * | a payload from an earlier generation on this connection | pool refcount reuse (the buffer 6.30.4 family) |
 * | neither — non-text noise | freed-chunk bytes from quiche's recv path (#415's hypothesis) |
 *
 * So [describeEchoCorruption] takes the other payloads that were in flight when it can, and says which
 * reading the bytes actually support. That is the datum nobody has had.
 *
 * Tests may use `ByteArray` freely; the no-ByteArray rule is production-only.
 */
public object EchoCorruption {
    /**
     * Decodes [received] as UTF-8, or throws an [AssertionError] carrying the full evidence.
     *
     * Call this *after* accumulating every byte, never per chunk — decoding as you read is what
     * destroys the evidence.
     *
     * @param expected what was sent, so divergence can be located.
     * @param received every byte that came back, in order.
     * @param chunks how many reads it took, which distinguishes "one corrupt delivery" from
     *   "reassembly went wrong across several".
     * @param alsoInFlight other payloads live at the same moment, if the caller knows them. Supplying
     *   these is what turns "these bytes are wrong" into "these bytes belong to *that* message".
     */
    public fun decodeOrFail(
        expected: String,
        received: ByteArray,
        chunks: Int,
        alsoInFlight: Collection<String> = emptyList(),
    ): String =
        try {
            received.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw AssertionError(describeEchoCorruption(expected, received, chunks, alsoInFlight), e)
        }

    /**
     * The evidence the bare decoder exception throws away, plus a reading of what the bytes support.
     *
     * Deliberately usable on its own: a caller whose bytes *decoded* but did not match still wants this
     * report, because a clean decode of the wrong message is the same defect wearing a friendlier face.
     */
    public fun describeEchoCorruption(
        expected: String,
        received: ByteArray,
        chunks: Int,
        alsoInFlight: Collection<String> = emptyList(),
    ): String {
        val expectedBytes = expected.encodeToByteArray()
        val divergence =
            received.indices.firstOrNull { i ->
                i >= expectedBytes.size || received[i] != expectedBytes[i]
            } ?: if (received.size == expectedBytes.size) -1 else received.size
        return buildString {
            appendLine("echo corruption: the peer returned bytes that were never sent (#401/#366/#415).")
            appendLine("  expected : \"$expected\" (${expectedBytes.size} bytes)")
            appendLine("  expected : ${expectedBytes.toHex()}")
            appendLine("  received : ${received.toHex()} (${received.size} bytes)")
            appendLine("  printable: ${received.toPrintable()}")
            appendLine("  chunks   : $chunks read(s); first divergence at index $divergence")
            append("  reading  : ${read(expected, received, alsoInFlight).summary}")
        }
    }

    /**
     * Which explanation the bytes support, as a value rather than a sentence.
     *
     * Sealed and exhaustive on purpose: this is a verdict about a root cause, and the three live
     * hypotheses have different owners and different fixes. A string would let a new shape be described
     * without anything forcing a decision about which family it belongs to — and this whole capture
     * exists because nobody could tell the families apart.
     *
     * Ordered most-specific first when computed: naming the message the bytes came from beats saying
     * they look like text, which beats saying they look like noise.
     */
    public sealed interface Reading {
        /** The human-readable reading, for the failure message. */
        public val summary: String

        /** Nothing came back. A truncation or a lost frame — not corruption, and must not be filed as one. */
        public data object NothingReturned : Reading {
            override val summary: String =
                "nothing came back at all — a truncation or a lost frame, not corruption."
        }

        /**
         * The bytes ARE another live payload, whole: a buffer reached the wrong reader.
         * Cross-connection or cross-stream leak — a different defect from #415's freed-chunk theory.
         */
        public data class AnotherPayloadWhole(
            val payload: String,
        ) : Reading {
            override val summary: String =
                "these bytes ARE another in-flight payload (\"${payload.take(60)}\") delivered whole to the " +
                    "wrong reader — a cross-connection/cross-stream leak, not allocator noise."
        }

        /** Another live payload spliced inside this one: one buffer handed to two owners. */
        public data class ContainsAnotherPayload(
            val payload: String,
        ) : Reading {
            override val summary: String =
                "these bytes CONTAIN another in-flight payload (\"${payload.take(60)}\") — a splice, so a " +
                    "buffer was handed to two owners rather than a chunk being freed under us."
        }

        /** A good prefix with an overwritten tail: reuse of a buffer this read still owned. */
        public data object PrefixThenOverwrittenTail : Reading {
            override val summary: String =
                "a prefix of the expected message followed by other bytes — the tail was overwritten in " +
                    "place, which points at reuse of a buffer still owned by this read."
        }

        /**
         * Text, but matching nothing the caller declared. Deliberately NOT a root-cause claim: the
         * capture says what it would need to make one instead of guessing.
         */
        public data object UnidentifiedText : Reading {
            override val summary: String =
                "mostly printable text that matches no payload this caller knows about — supply the other " +
                    "in-flight payloads via alsoInFlight to identify it, otherwise treat as a leak from " +
                    "outside this test's view."
        }

        /** Non-text noise: the shape a freed allocator chunk has, which is what #415 predicts. */
        public data class NonTextNoise(
            val printableBytes: Int,
            val totalBytes: Int,
        ) : Reading {
            override val summary: String =
                "$printableBytes of $totalBytes bytes are printable — non-text noise, which is what freed " +
                    "allocator chunks look like and is the shape #415 predicts."
        }
    }

    /** The typed verdict for these bytes. Exposed so a caller can branch on it, not just print it. */
    public fun read(
        expected: String,
        received: ByteArray,
        alsoInFlight: Collection<String> = emptyList(),
    ): Reading {
        if (received.isEmpty()) return Reading.NothingReturned

        alsoInFlight
            .firstOrNull { received.contentEquals(it.encodeToByteArray()) }
            ?.let { return Reading.AnotherPayloadWhole(it) }
        alsoInFlight
            .firstOrNull { it.isNotEmpty() && received.containsBytes(it.encodeToByteArray()) }
            ?.let { return Reading.ContainsAnotherPayload(it) }

        val expectedBytes = expected.encodeToByteArray()
        if (received.size == expectedBytes.size && received.startsWithPrefixOf(expectedBytes)) {
            return Reading.PrefixThenOverwrittenTail
        }
        val printable = received.count { it >= 0x20 && it < 0x7F }
        return if (printable * 10 >= received.size * 9) {
            Reading.UnidentifiedText
        } else {
            Reading.NonTextNoise(printableBytes = printable, totalBytes = received.size)
        }
    }

    private fun ByteArray.startsWithPrefixOf(other: ByteArray): Boolean {
        val n = minOf(size, other.size)
        var shared = 0
        while (shared < n && this[shared] == other[shared]) shared++
        return shared > 0
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..(size - needle.size)) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { b ->
            val v = b.toInt() and 0xFF
            "${HEX[v shr 4]}${HEX[v and 0xF]}"
        }

    private fun ByteArray.toPrintable(): String {
        val sb = StringBuilder(size)
        for (b in this) sb.append(if (b >= 0x20 && b < 0x7F) b.toInt().toChar() else '.')
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
