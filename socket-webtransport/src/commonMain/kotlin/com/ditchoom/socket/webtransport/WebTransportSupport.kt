package com.ditchoom.socket.webtransport

/**
 * Entry point for opening WebTransport sessions, surfaced as a **type-gated capability** (the v6
 * sealed-provider model, MAJOR_API_REDESIGN.md §5).
 *
 * The base [connect] works on every platform that has WebTransport at all. Native-only *power* — many
 * sessions over a single held HTTP/3 connection — is the separate [Multiplexed] sub-interface, present
 * only where the implementation supports it. Common code branches on it by `is`:
 *
 * ```
 * val wt = webTransportSupport()
 * val session = wt.connect("https://example.com/wt")              // works everywhere
 * if (wt is WebTransportSupport.Multiplexed) {                     // smart-cast: native-only power
 *     val held = wt.connectMultiplexed("https://example.com/wt")  // one H3 conn, many sessions
 *     val a = held.openSession("/a")
 *     val b = held.openSession("/b")
 *     held.close()
 * }
 * ```
 *
 * An unsupported capability is therefore a type you cannot reach — never a method that throws at
 * runtime.
 *
 * **Not `sealed`, deliberately:** the two providers are platform-source-set classes (native is a
 * [Multiplexed]; browser is not — the set of implemented interfaces *is* the per-platform difference),
 * and Kotlin's JS/Native backends prohibit implementing a `commonMain` sealed type from a platform
 * source set (it sees the common klib as a different module). The closed-set intent is kept by
 * construction instead: every implementation is `internal`, so the only way to obtain a
 * [WebTransportSupport] is [webTransportSupport] — no third party can add a provider.
 *
 * Obtain the platform's instance from [webTransportSupport].
 */
interface WebTransportSupport {
    /**
     * Open a single WebTransport session to [url] (an `https://` URL; the path is the WebTransport
     * resource). Available on every platform with WebTransport.
     *
     * On the browser this maps to `new WebTransport(url)`. On jvm/android/native it brings up an
     * HTTP/3 connection to the URL's authority and issues the Extended CONNECT — the connection is
     * owned by the returned session and torn down when the session is [WebTransportSession.close]d
     * (the held-lifetime model; see the native actual). For pooling several sessions over one
     * connection, use [Multiplexed] where present.
     *
     * @throws WebTransportException if the handshake/CONNECT fails or the peer lacks WebTransport.
     */
    suspend fun connect(
        url: String,
        options: WebTransportOptions = WebTransportOptions(),
    ): WebTransportSession

    /**
     * The native-only power variant: hold **one** HTTP/3 connection open and explicitly open many
     * sessions (each with its own streams/datagrams) over it, controlling its lifetime yourself.
     *
     * Present on jvm/android/native — including macOS/iOS, whose QUIC runs on Cloudflare quiche (over an
     * NWConnection-UDP datapath) via `socket-quic-quiche`, so they are full HTTP/3 platforms here, not
     * browsers. Absent in the browser **not** because it can't multiplex (it multiplexes streams within
     * a session, and pools connections transparently via [WebTransportOptions.allowPooling]) but because
     * it exposes no connection handle to hold — see the browser actual.
     */
    interface Multiplexed : WebTransportSupport {
        /**
         * Establish (and hold open) one HTTP/3 connection to [url]'s authority and return a handle for
         * opening multiple sessions over it. The held connection lives until [MultiplexedWebTransport.close].
         */
        suspend fun connectMultiplexed(
            url: String,
            options: WebTransportOptions = WebTransportOptions(),
        ): MultiplexedWebTransport
    }
}

/**
 * A held HTTP/3 connection over which many WebTransport sessions can be opened (native-only; obtained
 * from [WebTransportSupport.Multiplexed.connectMultiplexed]). The connection stays open until [close],
 * independent of any individual session's lifetime.
 */
interface MultiplexedWebTransport {
    /** Open another WebTransport session on the held connection, at [path] on the connection's authority. */
    suspend fun openSession(
        path: String,
        options: WebTransportOptions = WebTransportOptions(),
    ): WebTransportSession

    /** Close the held connection (and, with it, every session opened on it). Idempotent. */
    suspend fun close()
}

/**
 * The platform's WebTransport provider. Returns a [WebTransportSupport.Multiplexed] on jvm/android/native
 * and a plain [WebTransportSupport] in the browser. Pair with [networkCapabilities] to check that
 * WebTransport is present at all before calling.
 */
expect fun webTransportSupport(): WebTransportSupport
