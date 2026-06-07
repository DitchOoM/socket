package com.ditchoom.socket.quic.fuzz

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.QUIC_MAX_CONN_ID_LEN
import com.ditchoom.socket.quic.loadQuicheApi
import java.nio.ByteOrder

/**
 * Coverage-guided **Jazzer** fuzz target over `quiche_header_info` — the very first parse that runs on
 * every datagram the QUIC server receives, before any connection state exists (see
 * `CommonJvmWithQuicServer.receiveLoop`). A single unchecked read here is a whole-process
 * SIGABRT/SIGSEGV (the K/N sockaddr / cinterop crash history), so feeding it arbitrary bytes is the
 * highest-value thing to fuzz in the recv path.
 *
 * This is the dynamic counterpart to [com.ditchoom.socket.quic.QuicMalformedPacketTestSuite]: that suite
 * is a fixed regression floor; this drives the same entry point with libFuzzer's coverage feedback to
 * find inputs the hand-written list misses. libFuzzer (which Jazzer wraps) installs signal handlers, so a
 * native crash inside quiche is caught and saved as a `crash-*` repro input — exactly the class of bug a
 * fixed-vector test can't surface.
 *
 * **Run it** via the `quicHeaderFuzz` Gradle task (see socket-quic/build.gradle.kts). The target uses the
 * `byte[]` entry-point form, so it has **no compile-time dependency on Jazzer** — Jazzer is only needed
 * on the runtime classpath of that task, keeping it off the normal jvmTest dependency set.
 *
 * Intentionally NOT a `@Test`: a JUnit run would invoke it once with no input. It is reachable only
 * through the Jazzer driver, which calls [fuzzerTestOneInput] in a tight loop.
 */
object HeaderInfoFuzzer {
    private const val INPUT_CAP = 4096
    private const val MAX_TOKEN_LEN = 256

    private val api by lazy { loadQuicheApi() }
    private val factory = BufferFactory.Default

    // Scratch buffers, allocated once and reused across the (hot) fuzz loop. quiche writes the parsed
    // SCID/DCID/token + their lengths here; we never read them back — we only care that the parse
    // itself doesn't crash or corrupt native memory.
    private val inputBuf: PlatformBuffer by lazy { factory.allocate(INPUT_CAP) }
    private val versionBuf by lazy { factory.allocate(4) }
    private val typeBuf by lazy { factory.allocate(1) }
    private val scidBuf by lazy { factory.allocate(QUIC_MAX_CONN_ID_LEN) }
    private val scidLenBuf by lazy { factory.allocate(8) }
    private val dcidBuf by lazy { factory.allocate(QUIC_MAX_CONN_ID_LEN) }
    private val dcidLenBuf by lazy { factory.allocate(8) }
    private val tokenBuf by lazy { factory.allocate(MAX_TOKEN_LEN) }
    private val tokenLenBuf by lazy { factory.allocate(8) }

    private fun addr(buf: PlatformBuffer): Long = buf.nativeMemoryAccess!!.nativeAddress.toLong()

    /** Write a native size_t length output param to its capacity, mirroring the server's `initSizeTBuffer`. */
    private fun initSizeT(
        buf: PlatformBuffer,
        value: Int,
    ) {
        val bb = (buf.unwrapFully() as BaseJvmBuffer).byteBuffer
        bb.order(ByteOrder.nativeOrder())
        bb.putLong(0, value.toLong())
    }

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val len = if (data.size > INPUT_CAP) INPUT_CAP else data.size
        if (len == 0) return

        val bb = (inputBuf.unwrapFully() as BaseJvmBuffer).byteBuffer
        bb.clear()
        bb.put(data, 0, len)

        // Reset the length output params to the real capacities each iteration — quiche treats these as
        // in/out (max-on-entry, actual-on-exit), so a stale small value would make it under-read.
        initSizeT(scidLenBuf, QUIC_MAX_CONN_ID_LEN)
        initSizeT(dcidLenBuf, QUIC_MAX_CONN_ID_LEN)
        initSizeT(tokenLenBuf, MAX_TOKEN_LEN)

        // Return value is intentionally ignored: a negative rc (rejected header) is the *expected*,
        // healthy outcome for almost all fuzz inputs. The bug we are hunting is a crash / memory
        // corruption inside this call, which the Jazzer/libFuzzer signal handlers turn into a finding.
        api.headerInfo(
            addr(inputBuf),
            len,
            QUIC_MAX_CONN_ID_LEN,
            addr(versionBuf),
            addr(typeBuf),
            addr(scidBuf),
            addr(scidLenBuf),
            addr(dcidBuf),
            addr(dcidLenBuf),
            addr(tokenBuf),
            addr(tokenLenBuf),
        )
    }
}
