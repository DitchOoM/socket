package com.ditchoom.socket.webtransport

/**
 * The jvm/android/native WebTransport provider: a [WebTransportSupport.Multiplexed] backed by
 * socket-http3 / socket-quic (real QUIC on the classpath).
 *
 * ### Lifecycle (Fork 2 = option 1 now, evolve to 2)
 * [connect] returns a **held** session: a private, session-owned `CoroutineScope` runs
 * `withHttp3Connection(authority, port, webTransport = …) { connectWebTransport(authority, path); awaitCancellation() }`,
 * and the wrapping [WebTransportSession.close] cancels that scope (structured-concurrency teardown —
 * the connection can never be left in a half-open state). This keeps socket-quic's scope-only
 * `QuicScope` invariant untouched: there is no new unscoped `open()` primitive (rejected option 3).
 *
 * When [Multiplexed][WebTransportSupport.Multiplexed] lands (the Phase-4 DONE bar), [connect] becomes
 * sugar over [connectMultiplexed] + [MultiplexedWebTransport.openSession] so a single held HTTP/3
 * connection backs many sessions (option 2).
 *
 * The session adapter itself is already real — see [NativeWebTransportSession]. Only the
 * held-connection plumbing below is the Phase-4 fan-out step that the gate stops short of.
 */
internal class Http3WebTransportSupport : WebTransportSupport.Multiplexed {
    override suspend fun connect(
        url: String,
        options: WebTransportOptions,
    ): WebTransportSession {
        // Phase-4 fan-out: bring up the held HTTP/3 connection + session per the lifecycle KDoc above,
        // then wrap the resulting socket-http3 WebTransportSession in NativeWebTransportSession and pair
        // it with the owning scope so close() cancels withHttp3Connection.
        TODO("Phase 4: held single-session connect(url) over withHttp3Connection (Fork 2 option 1)")
    }

    override suspend fun connectMultiplexed(
        url: String,
        options: WebTransportOptions,
    ): MultiplexedWebTransport {
        // Phase-4 fan-out + DONE bar: hold one HTTP/3 connection open under a private scope and let
        // openSession(path) call connectWebTransport(authority, path) repeatedly over it; close()
        // cancels the scope (tears down every session).
        TODO("Phase 4 DONE bar: held multi-session connectMultiplexed over one withHttp3Connection")
    }
}

/** jvm/android/native: real QUIC on the classpath, so WebTransport is multiplexed-capable. */
actual fun webTransportSupport(): WebTransportSupport = Http3WebTransportSupport()
