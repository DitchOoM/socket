package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The browser stream wrappers' mapping of a rejected WHATWG read/write onto the neutral API, driven with
 * the exact rejection reasons Chrome produces (no browser needed: the reasons are plain JS values).
 * The wrappers bound each read/write with `withTimeout`, so each test runs on the real event loop where
 * the rejected promise settles, not on the test's virtual clock.
 */
class BrowserStreamRejectionJsTest {
    @Test
    fun write_stopSendingWithCode_isReportedStreamAbort() =
        onEventLoop {
            val e = assertFailsWith<WebTransportStreamException> { writeRejectedWith(stopSendingError(487)) }
            assertEquals(487u, e.errorCode)
        }

    @Test
    fun write_chromeCodelessRemoteAbort_isStreamAbortWithoutCode() =
        onEventLoop {
            val e = assertFailsWith<WebTransportStreamAbortedWithoutCodeException> { writeRejectedWith(chromeRemoteAbort()) }
            assertEquals(WebTransportFailure.StreamAbortedWithoutCode, e.failure)
        }

    @Test
    fun write_sessionClosed_isNotAStreamAbort() =
        onEventLoop {
            val thrown = runCatching { writeRejectedWith(sessionClosedError()) }.exceptionOrNull()
            assertFalse(thrown is WebTransportStreamAbortException, "a session close is not a stream abort: $thrown")
        }

    @Test
    fun write_localAbort_isNotAStreamAbort() =
        onEventLoop {
            val thrown = runCatching { writeRejectedWith(localAbort()) }.exceptionOrNull()
            assertFalse(thrown is WebTransportStreamAbortException, "a local abort is not a peer abort: $thrown")
        }

    @Test
    fun read_resetStreamWithCode_isReset() = onEventLoop { assertEquals(ReadResult.Reset, readRejectedWith(resetStreamError(487))) }

    @Test
    fun read_chromeCodelessRemoteAbort_isReset() = onEventLoop { assertEquals(ReadResult.Reset, readRejectedWith(chromeRemoteAbort())) }

    @Test
    fun read_sessionClosed_isEnd() = onEventLoop { assertEquals(ReadResult.End, readRejectedWith(sessionClosedError())) }

    private suspend fun writeRejectedWith(reason: dynamic) {
        BrowserSendStream(rejectingWriter(reason)).write("x".toReadBuffer(Charset.UTF8))
    }

    private suspend fun readRejectedWith(reason: dynamic): ReadResult = BrowserReceiveStream(rejectingReader(reason)).read()
}

private fun onEventLoop(block: suspend () -> Unit): TestResult = runTest { withContext(Dispatchers.Default) { block() } }

private fun rejectingWriter(reason: dynamic): WritableStreamDefaultWriterJs =
    js(
        "({ write: function () { return Promise.reject(reason); }, close: function () { return Promise.resolve(); }, " +
            "abort: function () { return Promise.resolve(); } })",
    )

private fun rejectingReader(reason: dynamic): ReadableStreamDefaultReaderJs =
    js("({ read: function () { return Promise.reject(reason); }, cancel: function () { return Promise.resolve(); } })")

/** blink `WebTransport::OnReceivedStopSending`: a WebTransportError carrying the peer's code. */
private fun stopSendingError(code: Int): dynamic =
    js("Object.assign(new DOMException('Received STOP_SENDING.', 'WebTransportError'), { source: 'stream', streamErrorCode: code })")

/** blink `WebTransport::OnReceivedResetStream`: a WebTransportError carrying the peer's code. */
private fun resetStreamError(code: Int): dynamic =
    js("Object.assign(new DOMException('Received RESET_STREAM.', 'WebTransportError'), { source: 'stream', streamErrorCode: code })")

/** blink `OutgoingStream::HandlePipeClosed` / `IncomingStream::ProcessClose`: a code-less NetworkError. */
private fun chromeRemoteAbort(): dynamic = js("new DOMException('The stream was aborted by the remote server', 'NetworkError')")

/** blink `WebTransport::OnClosed`: a session-sourced WebTransportError with no stream code. */
private fun sessionClosedError(): dynamic =
    js("Object.assign(new DOMException('The session is closed.', 'WebTransportError'), { source: 'session', streamErrorCode: null })")

/** blink `OutgoingStream::CreateAbortException(local)`: the page's own abort. */
private fun localAbort(): dynamic = js("new DOMException('The stream was aborted locally', 'AbortError')")
