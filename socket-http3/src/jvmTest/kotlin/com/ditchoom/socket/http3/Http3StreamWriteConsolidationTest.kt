package com.ditchoom.socket.http3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every byte HTTP/3 puts on a stream must go through [Http3StreamWriter], which calls
 * `ByteSink.writeFully`. A bare `stream.write(...)` returns
 * [BytesWritten][com.ditchoom.buffer.flow.BytesWritten] and may be **partial**; a caller that discards
 * the count truncates the frame, and for a length-prefixed protocol that is corruption rather than
 * loss — the peer reads on to the already-declared length and swallows the frames that follow, and the
 * stream never re-aligns.
 *
 * Every write site in this module had exactly that bug, in four separate copies of the same
 * encode-write-free triple. Consolidating them is what made it fixable once; this guard is what keeps
 * them consolidated, because a new direct `write` silently opts back out of the fix.
 *
 * Implemented as an **inventory ratchet** rather than a cleverer parse: the only legitimate direct
 * `write` calls are implementations of the sink contract itself, which must faithfully report a partial
 * count to *their* caller. Those are enumerated below. Any new occurrence — or the disappearance of an
 * expected one — fails, which is the point: adding a write path should be a deliberate act.
 */
class Http3StreamWriteConsolidationTest {
    /**
     * Files permitted to call `.write(` on a stream, and how many times.
     *
     * `WebTransportStreams.kt` implements `ByteSink.write` for the WebTransport bidi/uni stream types
     * and delegates to the underlying QUIC stream, propagating the partial count verbatim — correct,
     * and the reason it is not routed through the writer.
     */
    private val sinkImplementations = mapOf("WebTransportStreams.kt" to 2)

    @Test
    fun onlySinkImplementationsWriteToAStreamDirectly() {
        val commonMain = File(repoRoot(), "socket-http3/src/commonMain/kotlin")
        assertTrue(commonMain.isDirectory, "HTTP/3 commonMain is missing at $commonMain — guard is stale")

        val found = mutableMapOf<String, Int>()
        val sites = mutableListOf<String>()
        commonMain
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readText().split("\n").forEachIndexed { index, line ->
                    // Match the expression, not the prose: this very rule is DESCRIBED in
                    // Http3StreamWriter's KDoc, which a naive grep then reports as a violation.
                    if (line.isComment()) return@forEachIndexed
                    if (!STREAM_WRITE.containsMatchIn(line)) return@forEachIndexed
                    found[file.name] = (found[file.name] ?: 0) + 1
                    sites += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }

        if (found != sinkImplementations) {
            fail(
                "HTTP/3 stream-write inventory changed.\n" +
                    "expected: $sinkImplementations\n" +
                    "found:    $found\n\n" +
                    "A direct stream.write(...) may accept only PART of the buffer; discarding the " +
                    "returned count truncates the frame and desynchronizes the stream. Route the write " +
                    "through Http3StreamWriter (writeFrame / writeVarInts / writeEncoderInstruction / " +
                    "writeDecoderInstruction), which calls ByteSink.writeFully.\n" +
                    "Only update the inventory above if the new site genuinely IMPLEMENTS " +
                    "ByteSink.write and propagates the partial count to its caller.\n\n" +
                    sites.joinToString("\n"),
            )
        }
    }

    /**
     * A line-comment or KDoc/block-comment body line. Deliberately crude — it can only ever cause the
     * guard to look at FEWER lines, and a real write call never begins with a comment marker.
     */
    private fun String.isComment(): Boolean {
        val t = trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        /**
         * A stream-ish receiver's `.write(`. `buffer.write(...)` on a `WriteBuffer` is a different
         * method that returns Unit, so buffer receivers are deliberately not matched.
         */
        val STREAM_WRITE = Regex("""\b(\w*[Ss]tream|control|delegate|sink)\w*\.write\s*\(""")
    }
}
