package com.ditchoom.socket.webtransport

/**
 * The browser WebTransport provider (wasmJs). Same shape as the js actual — a plain
 * [WebTransportSupport] wrapping the platform `WebTransport` object, pulling neither socket-http3 nor
 * socket-quic.
 *
 * **Stub for this step.** The js bridge lands first (see the js source set); the wasmJs bridge is the
 * immediate follow-up. wasmJs WHATWG-stream interop is materially different from js — `@JsFun` + `JsAny`
 * rather than `dynamic`/`external class`, and `Uint8Array` ↔ WASM-linear-memory copies (the
 * buffer-compression module is the precedent) — so it is implemented separately rather than shared in
 * `browserMain`. The module compiles on wasmJs today; [connect] is wired in the follow-up.
 */
internal class BrowserWebTransportSupport : WebTransportSupport {
    override suspend fun connect(
        url: String,
        options: WebTransportOptions,
    ): WebTransportSession = TODO("wasmJs WebTransport bridge: new WebTransport(url) via @JsFun/JsAny (js bridge first)")
}

actual fun webTransportSupport(): WebTransportSupport = BrowserWebTransportSupport()
