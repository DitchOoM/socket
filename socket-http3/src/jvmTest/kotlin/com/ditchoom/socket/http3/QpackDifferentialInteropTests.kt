package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.http3.Http3FuzzGenerators.SeededEntropy
import com.ditchoom.socket.http3.Http3FuzzGenerators.headerFields
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Differential** QPACK (RFC 9204) fuzzing against an INDEPENDENT implementation — aioquic's `pylsqpack`
 * (a binding to ls-qpack), driven through the local `socket-http3/qpack-diff/` HTTP oracle. This is the
 * external counterpart to the in-process [Http3RoundTripFuzzTests]: that fuzzer runs our encoder through
 * OUR OWN decoder, so a bug symmetric across both halves hides. ls-qpack cannot be wrong the same way, so
 * this cross-checks BOTH directions the loopback can't isolate, over many randomly-generated header lists:
 *
 *  - [oursEncode_refDecode] — our [QpackEncoder]'s field section + serialized encoder-stream inserts must
 *    decode in ls-qpack back to the exact header list (raw, per-section, fuzzed — the existing
 *    [Http3DockerInteropTests] proves this only over a full H3/QUIC connection).
 *  - [refEncode_oursDecode] — ls-qpack's field section + encoder stream must decode in our [QpackDecoder]
 *    back to the exact header list. **This direction has no other coverage** (the loopback and docker
 *    interop both exercise only our encoder against a foreign decoder).
 *
 * **Skip-on-unreachable, never flaky-fail** (mirrors [Http3DockerInteropTests]): if the oracle is not up
 * on `127.0.0.1:$PORT` the test logs a SKIP and returns. Start it with
 * `socket-http3/qpack-diff/run-qpack-diff.sh` (Docker) or a `pip install pylsqpack` venv. A mismatch or a
 * ref-side QPACK error AFTER the oracle is reached is a real differential regression and fails.
 */
class QpackDifferentialInteropTests {
    private val port = System.getenv("QPACK_DIFF_PORT")?.toIntOrNull() ?: 4434
    private val base = "http://127.0.0.1:$port"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    private fun pool() = BufferPool(threadingMode = ThreadingMode.SingleThreaded, factory = BufferFactory.Default)

    // ---- byte/hex plumbing --------------------------------------------------------------------------

    private fun bufferOf(bytes: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        return buf
    }

    private fun ReadBuffer.toBytes(): ByteArray {
        val saved = position()
        val out = ByteArray(remaining())
        for (i in out.indices) out[i] = readByte()
        position(saved)
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun hexToBytes(s: String): ByteArray =
        if (s.isEmpty()) {
            ByteArray(
                0,
            )
        } else {
            ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
        }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

    // ---- oracle transport ---------------------------------------------------------------------------

    private fun reachable(): Boolean =
        try {
            val resp =
                http.send(
                    HttpRequest
                        .newBuilder(URI("$base/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            resp.statusCode() == 200
        } catch (_: Exception) {
            false
        }

    private fun post(body: String): String {
        val request =
            HttpRequest
                .newBuilder(URI("$base/qpack"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        // Retry once on a transport IOException: if a pooled keep-alive connection is reset between
        // requests the resend opens a fresh one. A ref-side QPACK error is a non-200 response (handled
        // by the check below), not an IOException, so this never masks a real differential signal.
        val resp =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (_: IOException) {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            }
        check(resp.statusCode() == 200) { "qpack oracle returned ${resp.statusCode()}: ${resp.body().trim()}" }
        return resp.body()
    }

    private fun headerLines(fields: List<QpackHeaderField>): String =
        fields.joinToString("") { "h=${it.name.utf8().toHex()}:${it.value.utf8().toHex()}\n" }

    private fun parseHeaders(body: String): List<QpackHeaderField> =
        body
            .lineSequence()
            .filter { it.startsWith("h=") }
            .map { line ->
                val payload = line.removePrefix("h=")
                val (nameHex, valueHex) = payload.split(":", limit = 2)
                QpackHeaderField(String(hexToBytes(nameHex), Charsets.UTF_8), String(hexToBytes(valueHex), Charsets.UTF_8))
            }.toList()

    private fun parseField(
        body: String,
        key: String,
    ): String =
        body
            .lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            ?.trim()
            .orEmpty()

    // ---- the two differential directions ------------------------------------------------------------

    /** Our encoder's output (field section + encoder-stream inserts) must decode in ls-qpack to [fields]. */
    private suspend fun oursEncodeRefDecode(
        fields: List<QpackHeaderField>,
        capacity: Long,
        blocked: Long,
        streamId: Long,
    ) {
        val instructions = mutableListOf<QpackEncoderInstruction>()
        val encoder = QpackEncoder(capacity, blocked) { instructions += it }
        if (capacity > 0) encoder.setCapacity(capacity)
        val section = encoder.encodeSection(fields, streamId, pool())

        // Serialize the encoder-stream instructions (Set Capacity + inserts) into ls-qpack's wire format.
        val encStream = BufferFactory.Default.allocate(65_536)
        for (instr in instructions) QpackEncoderInstructionCodec.encode(encStream, instr)
        encStream.resetForRead()

        val esHex = encStream.toBytes().toHex()
        val frHex = section.toBytes().toHex()
        val body =
            buildString {
                append("op=decode\ncapacity=$capacity\nblocked=$blocked\nstream=$streamId\n")
                append("encoder_stream=$esHex\n")
                append("frame=$frHex\n")
            }
        // Capture the full repro (the wire bytes ls-qpack saw) on ANY failure — the oracle POST or the
        // header-equality assertion — then rethrow. Covers both directions now via the shared helper.
        withDiffDebug(
            "ours-encode->ref-decode",
            { "cap=$capacity blocked=$blocked stream=$streamId es=$esHex fr=$frHex fields=$fields" },
        ) {
            assertEquals(fields, parseHeaders(post(body)), "ours-encode -> ref-decode (cap=$capacity)")
        }
    }

    /** ls-qpack's output (field section + encoder stream) must decode in OUR decoder to [fields]. */
    private suspend fun refEncodeOursDecode(
        fields: List<QpackHeaderField>,
        capacity: Long,
        blocked: Long,
        streamId: Long,
    ) {
        val resp =
            post(
                buildString {
                    append("op=encode\ncapacity=$capacity\nblocked=$blocked\nstream=$streamId\n")
                    append(headerLines(fields))
                },
            )
        val encStream = hexToBytes(parseField(resp, "encoder_stream"))
        val frame = hexToBytes(parseField(resp, "frame"))

        // Capture the ls-qpack-produced wire (the bytes OUR decoder choked on) on any decode error or
        // mismatch, then rethrow — the previously-uninstrumented direction now self-reports its repro too.
        withDiffDebug(
            "ref-encode->ours-decode",
            { "cap=$capacity blocked=$blocked stream=$streamId es=${encStream.toHex()} fr=${frame.toHex()} fields=$fields" },
        ) {
            val decoder = QpackDecoder(capacity) { /* ignore decoder-stream acks for this one-shot decode */ }
            if (encStream.isNotEmpty()) {
                val buf = bufferOf(encStream)
                while (buf.hasRemaining()) decoder.applyEncoderInstruction(QpackEncoderInstructionCodec.decode(buf, scratchPool = null))
            }
            val decoded = decoder.decodeSection(bufferOf(frame), streamId, scratchPool = null)
            assertEquals(fields, decoded, "ref-encode -> ours-decode (cap=$capacity)")
        }
    }

    // ---- tests --------------------------------------------------------------------------------------

    private val regimes = listOf(0L to 0L, 256L to 16L, 4096L to 16L) // capacity to blocked

    /**
     * A non-empty header list. An empty field section is spec-legal (RFC 9204 §4.5: "a possibly empty
     * sequence of representations") and our codec round-trips it, but it never occurs in real HTTP/3
     * (every message carries pseudo-headers) and ls-qpack's `lsqpack_dec_header_in` rejects a zero-field
     * block — a known impl divergence on a degenerate input, not an interop case. The in-process
     * [Http3RoundTripFuzzTests] still covers the empty section; the external differential stays realistic.
     */
    private fun realistic(e: Http3FuzzGenerators.Entropy): List<QpackHeaderField> =
        headerFields(e).ifEmpty { listOf(QpackHeaderField(":path", "/")) }

    @Test
    fun oursEncode_refDecode() {
        if (!reachable()) {
            println("SKIP QpackDifferentialInteropTests: qpack-diff oracle not reachable on $base (start qpack-diff/run-qpack-diff.sh)")
            return
        }
        runBlocking {
            val e = SeededEntropy(0x9AC0_0001)
            for (i in 0 until 600) {
                val (capacity, blocked) = regimes[i % regimes.size]
                oursEncodeRefDecode(realistic(e), capacity, blocked, streamId = (i * 4).toLong())
            }
        }
    }

    @Test
    fun refEncode_oursDecode() {
        if (!reachable()) {
            println("SKIP QpackDifferentialInteropTests: qpack-diff oracle not reachable on $base (start qpack-diff/run-qpack-diff.sh)")
            return
        }
        runBlocking {
            val e = SeededEntropy(0x9AC0_0002)
            for (i in 0 until 600) {
                val (capacity, blocked) = regimes[i % regimes.size]
                refEncodeOursDecode(realistic(e), capacity, blocked, streamId = (i * 4).toLong())
            }
        }
    }
}
