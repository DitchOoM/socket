package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.decodeHexInto
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.buffer.wrap
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Int8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **Browser** WebTransport conformance, driven through the *production* `browserMain` wrapper
 * ([webTransportSupport] → `BrowserWebTransportSupport`, which wraps the platform `WebTransport` object)
 * in **real headless Chrome via Karma**. Unlike the JVM/Linux/Apple [WebTransportTestSuite], the browser
 * has no in-process QUIC server, so this dials an **externally-launched** `withHttp3Server`
 * ([BrowserInteropServer]) presenting the `pinned` EC P-256 leaf, accepted via the W3C
 * `serverCertificateHashes` trust check (the only self-signed path a browser allows).
 *
 * The server's ephemeral port + leaf SHA-256 are injected at build time through the generated
 * [BrowserInteropConfig] (the orchestration script starts the server, then runs `jsBrowserTest`). When no
 * server was configured ([BrowserInteropConfig.URL] empty — e.g. a plain `jsBrowserTest`), every test
 * **skips** so this never fails a server-less run. The whole source set is opt-in behind
 * `-PwtBrowserInterop`, so default builds/CI never require Chrome.
 *
 * Tests return a `Promise` (Kotlin/JS async test) so real WebTransport I/O runs on the browser event loop.
 */
@OptIn(DelicateCoroutinesApi::class)
class BrowserWebTransportInteropTest {
    @Test
    fun bidiRoundTrip() =
        GlobalScope.promise {
            val base = BrowserInteropConfig.URL
            if (base.isEmpty()) return@promise // no interop server configured → skip
            val session = webTransportSupport().connect("${base}wt", pinnedOptions())
            try {
                assertEquals("echo:hello", session.roundTripBidi("hello"))
            } finally {
                session.close()
            }
        }

    @Test
    fun twoSessionsEachRoundTrip() =
        GlobalScope.promise {
            val base = BrowserInteropConfig.URL
            if (base.isEmpty()) return@promise // no interop server configured → skip
            // Two sessions (the browser pools their HTTP/3 connection transparently); each round-trips
            // its own bidi stream — the browser analogue of the multiplexed Phase-4 DONE bar.
            val a = webTransportSupport().connect("${base}a", pinnedOptions())
            val b = webTransportSupport().connect("${base}b", pinnedOptions())
            try {
                assertEquals("echo:from-a", a.roundTripBidi("from-a"))
                assertEquals("echo:from-b", b.roundTripBidi("from-b"))
            } finally {
                a.close()
                b.close()
            }
        }

    @Test
    fun streamReset_surfacesNeutralExceptionWithCode() =
        GlobalScope.promise {
            val base = BrowserInteropConfig.URL
            if (base.isEmpty()) return@promise // no interop server configured → skip
            // The `/reset` route makes the server abort the stream with 0x1e7 (kept in sync with
            // BrowserInteropServer.BROWSER_RESET_CODE). The browser must surface it as the SAME neutral
            // WebTransportStreamException + 32-bit UInt code the native backends do (cross-backend parity).
            val session = webTransportSupport().connect("${base}reset", pinnedOptions())
            try {
                val observed =
                    withTimeout(5.seconds) {
                        val stream = session.openBidiStream()
                        stream.write("hello".toReadBuffer(Charset.UTF8))
                        var code: UInt? = null
                        while (code == null) {
                            try {
                                stream.write("x".toReadBuffer(Charset.UTF8))
                                delay(25)
                            } catch (e: WebTransportStreamException) {
                                code = e.errorCode
                            }
                        }
                        code
                    }
                assertEquals(0x1e7u, observed)
            } finally {
                session.close()
            }
        }

    private fun pinnedOptions(): WebTransportOptions =
        WebTransportOptions(
            serverCertificateHashes = listOf(WebTransportCertificateHash(hexToBuffer(BrowserInteropConfig.CERT_SHA256_HEX))),
        )
}

/** Decode a hex string into a read-ready [PlatformBuffer] (the 32-byte sha-256 leaf hash). */
private fun hexToBuffer(hex: String): PlatformBuffer {
    // Use buffer's own hex decoder (ReadBuffer.decodeHexInto — validates digits). It is buffer→buffer, so
    // the destination is a wrapped zeroed Int8Array (the JS binary boundary; a one-shot String.hexToBuffer
    // exists only in buffer-crypto, which isn't on the browser classpath).
    val dest = PlatformBuffer.wrap(Int8Array(hex.length / 2))
    hex.toReadBuffer(Charset.UTF8).decodeHexInto(dest) // hex digits are single-byte (ASCII⊂UTF-8); JS encodes only UTF-8
    dest.resetForRead()
    return dest
}

/** Open a bidi stream, send [msg], half-close the send side, read the echoed reply to end-of-stream. */
private suspend fun WebTransportSession.roundTripBidi(msg: String): String {
    val stream = openBidiStream()
    stream.write(msg.toReadBuffer(Charset.UTF8)) // String → ReadBuffer directly (no ByteArray hop)
    (stream as com.ditchoom.buffer.flow.HalfCloseable).shutdownSend()
    return withTimeout(5.seconds) { stream.readUtf8() }
}

private suspend fun ByteStream.readUtf8(): String {
    val sb = StringBuilder()
    while (true) {
        when (val result = read()) {
            is ReadResult.Data -> {
                sb.append(result.buffer.readString(result.buffer.remaining(), Charset.UTF8))
                result.buffer.freeIfNeeded()
            }
            ReadResult.End, ReadResult.Reset -> return sb.toString()
        }
    }
}
