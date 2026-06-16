@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The browser WebTransport provider (wasmJs). Same shape and contract as the js actual — a plain
 * [WebTransportSupport] wrapping the platform `WebTransport` object, pulling neither socket-http3 nor
 * socket-quic (the browser does HTTP/3 internally; "honesty by construction", §5). The only platform
 * difference from js is the WHATWG-stream interop model (`@JsFun`/`JsAny` + linear-memory copies; see
 * [WebTransportWasmExternals][createWebTransport]).
 *
 * Within-session multiplexing (many streams + datagrams over one session) is fully present. A plain
 * [WebTransportSupport], **not** [WebTransportSupport.Multiplexed]: the browser pools connections
 * transparently via [WebTransportOptions.allowPooling] but exposes no connection handle to hold — so the
 * explicit held-connection power is a type a browser build cannot reach (the type-gate, not a stub).
 */
internal class BrowserWebTransportSupport : WebTransportSupport {
    override suspend fun connect(
        url: String,
        options: WebTransportOptions,
    ): WebTransportSession {
        // W3C WebTransport serverCertificateHashes: [{ algorithm, value: BufferSource }]. The browser
        // treats these as the sole trust check (no chain validation). Each value is copied to a fresh
        // Uint8Array at the wasm/JS boundary; toJsUint8Array does not advance the source buffer.
        val certHashes = jsNewArray()
        for (hash in options.serverCertificateHashes) {
            jsPushCertHash(certHashes, hash.algorithm, hash.value.toJsUint8Array(hash.value.remaining()))
        }
        val wt = createWebTransport(url, makeWtInit(options.allowPooling, certHashes))
        try {
            wt.ready.await()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw WebTransportException("WebTransport connect to $url failed: ${t.message}", t)
        }
        return BrowserWebTransportSession(wt)
    }
}

/** wasmJs: WebTransport is present but single-session (no Multiplexed type to reach). */
actual fun webTransportSupport(): WebTransportSupport = BrowserWebTransportSupport()

/**
 * A [WebTransportSession] backed by the browser `WebTransport` object. Streams/datagrams bridge the
 * WHATWG streams onto the byte trichotomy (see [BrowserBidiStream]/[BrowserSendStream]/[BrowserReceiveStream]);
 * the `Uint8Array` ↔ native-buffer copy is the sanctioned wasm/JS boundary copy (§9).
 */
internal class BrowserWebTransportSession(
    private val wt: WebTransportJs,
) : WebTransportSession {
    // wasmJs runs on the single JS event loop, so plain vars are safe here.
    private val scope = CoroutineScope(SupervisorJob())
    private val closedSignal = CompletableDeferred<WebTransportCloseInfo>()
    private var _closeInfo: WebTransportCloseInfo? = null

    // One writer for the session's outbound datagrams (getWriter locks the stream, so reuse it).
    private val datagramWriter by lazy { wt.datagrams.writable.getWriter() }

    init {
        // Watch the peer/transport close so isClosed/closeInfo reflect it even without an awaitClosed caller.
        scope.launch {
            val info =
                try {
                    wt.closed.await().let { WebTransportCloseInfo(it.closeCode, it.reason) }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    WebTransportCloseInfo(0, t.message ?: "session error")
                }
            finish(info)
        }
    }

    override val isClosed: Boolean get() = _closeInfo != null
    override val closeInfo: WebTransportCloseInfo? get() = _closeInfo

    override suspend fun awaitClosed(): WebTransportCloseInfo = closedSignal.await()

    override suspend fun openBidiStream(): ByteStream = BrowserBidiStream(wt.createBidirectionalStream().await())

    override suspend fun openUniStream(): ByteSink = BrowserSendStream(wt.createUnidirectionalStream().await().getWriter())

    override val incomingBidiStreams: Flow<ByteStream>
        get() =
            readableStreamFlow(wt.incomingBidirectionalStreams) {
                BrowserBidiStream(it.unsafeCast<WebTransportBidirectionalStreamJs>())
            }

    override val incomingUniStreams: Flow<ByteSource>
        get() =
            readableStreamFlow(wt.incomingUnidirectionalStreams) {
                BrowserReceiveStream(it.unsafeCast<ReadableStreamJs>().getReader())
            }

    override suspend fun sendDatagram(payload: ReadBuffer) {
        val n = payload.remaining()
        val chunk = payload.toJsUint8Array(n)
        datagramWriter.write(chunk).await()
        payload.position(payload.position() + n)
    }

    override val datagrams: Flow<ReadBuffer>
        get() = readableStreamFlow(wt.datagrams.readable) { it.uint8ArrayToReadBuffer() }

    override suspend fun close(
        code: Int,
        reason: String,
    ) {
        if (isClosed) return
        try {
            wt.close(makeCloseInfo(code, reason))
        } catch (_: Throwable) {
            // Already gone — fall through to local teardown.
        }
        finish(WebTransportCloseInfo(code, reason))
    }

    private fun finish(info: WebTransportCloseInfo) {
        if (_closeInfo != null) return
        _closeInfo = info
        closedSignal.complete(info)
        scope.cancel()
    }
}

/**
 * Drive a WHATWG `ReadableStream` as a cold [Flow]: acquire a reader on collection, loop `read()` until
 * `done`, and map each chunk. On early collector cancellation the reader is cancelled (fire-and-forget,
 * since we may already be in a cancelled context).
 */
private fun <T> readableStreamFlow(
    stream: ReadableStreamJs,
    map: (JsAny) -> T,
): Flow<T> =
    flow {
        val reader = stream.getReader()
        try {
            while (true) {
                val chunk = reader.read().await()
                if (chunk.done) break
                emit(map(chunk.value!!))
            }
        } finally {
            runCatching { reader.cancel(null) } // fire-and-forget; do not await in a (possibly) cancelled finally
        }
    }
