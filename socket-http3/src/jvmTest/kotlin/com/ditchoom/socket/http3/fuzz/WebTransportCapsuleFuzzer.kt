package com.ditchoom.socket.http3.fuzz

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.http3.CapsuleParse
import com.ditchoom.socket.http3.Http3StreamException
import com.ditchoom.socket.http3.WebTransportWire

/**
 * Coverage-guided **Jazzer** fuzz target over the WebTransport-over-HTTP/3 **Capsule Protocol** parser
 * (RFC 9297 §3 / draft-ietf-webtrans-http3 §5–§6) — [WebTransportWire.nextCapsule]. This is the peer's
 * CONNECT-stream control channel: the attacker-controlled `Type (varint) · Length (varint) · Value`
 * framing plus the WT_CLOSE_SESSION value decode (32-bit code + UTF-8 reason). It was the only
 * adversarial parser in the WebTransport stack with no dedicated fuzzer — the H3 codec fuzzer covers
 * DATA-frame reassembly, but not the capsule layer carried *inside* those frames.
 *
 * Like [Http3CodecFuzzer] the code under test is **pure Kotlin**, so Jazzer's JVM instrumentation gives
 * genuine edge coverage. The Jazzer driver hands each input in as a `byte[]`; it is copied into a
 * [StreamProcessor] once (mirroring the mux's real `appendCopy`), then drained through `nextCapsule` —
 * exactly as `WebTransportMux.parseCapsules` drives it, minus the session dispatch (which suspends and
 * has side effects, and is not parser logic).
 *
 * **Invariant** (the bug being hunted): `nextCapsule` either returns a [CapsuleParse] or throws a typed
 * [Http3StreamException] (a value too short for its declared length, etc.). ANY other `Throwable` — a
 * raw buffer underflow, [IndexOutOfBoundsException], a negative-count read from a truncated `Length`
 * varint, OOM, or a hang — bubbles out and Jazzer records a `crash-*` repro. (Fuzzing this parser is
 * what motivated keeping the capsule `Length` in `Long` through the bounds check; see `nextCapsule`.)
 *
 * Reached only through the Jazzer driver (uses the `byte[]` entry-point form, so no compile-time Jazzer
 * dependency); intentionally NOT a `@Test`. Run via the `wtCapsuleFuzz` Gradle task.
 */
object WebTransportCapsuleFuzzer {
    private const val INPUT_CAP = 4096
    private val factory = BufferFactory.Default

    private fun pool() =
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize = 8,
            defaultBufferSize = 256,
            factory = factory,
        )

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val len = if (data.size > INPUT_CAP) INPUT_CAP else data.size
        if (len == 0) return
        // The single byte[] → buffer conversion at the driver ABI boundary; everything below is buffers.
        val chunk =
            factory.allocate(len).apply {
                writeBytes(data, 0, len)
                resetForRead()
            }
        val capsules = StreamProcessor.create(pool(), ByteOrder.BIG_ENDIAN)
        capsules.append(chunk)
        try {
            // Drain every whole capsule buffered; NeedMore is the terminal state for a fixed input.
            // Each non-NeedMore step consumes >= 2 bytes (Type + Length varints), so this terminates.
            while (WebTransportWire.nextCapsule(capsules) != CapsuleParse.NeedMore) Unit
        } catch (e: Http3StreamException) {
            // Expected: the parser rejected a malformed capsule with a typed error. Not a finding.
        } finally {
            capsules.release()
        }
    }
}
