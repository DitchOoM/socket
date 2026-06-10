---
sidebar_position: 1
title: QUIC Overview
---

# QUIC

`socket-quic` is a sister artifact that adds a QUIC (RFC 9000) client and server with the same
scope-based, suspend-based shape as the core `socket` module — and the same zero-copy buffer
discipline. It lives in a separate dependency so TCP-only consumers don't pay for it.

```kotlin
dependencies {
    implementation("com.ditchoom:socket-quic:<latest-version>")
}
```

Find the latest version on [Maven Central](https://central.sonatype.com/search?q=com.ditchoom).

## Why QUIC?

- **Multiplexed streams** — many independent bidirectional/unidirectional streams over one
  connection, with no head-of-line blocking between them.
- **Always encrypted** — TLS 1.3 is built into the handshake; there is no plaintext QUIC.
- **Unreliable datagrams** (RFC 9221) — fire-and-forget messages alongside reliable streams.
- **Connection migration** (RFC 9000 §9) — survive a local address change without a new handshake.
- **Same API everywhere** — one Kotlin codebase across JVM, Android, Linux native, and Apple.

## Platform Support

| Platform | Backend | Notes |
|----------|---------|-------|
| JVM / Android | [quiche](https://github.com/cloudflare/quiche) | FFM on JDK 21+, JNI on JDK ≤20 |
| Linux (x64/arm64) | quiche cinterop | Static `libquiche.a`, io_uring UDP |
| Apple (iOS/macOS/…) | [Network.framework](https://developer.apple.com/documentation/network) | Native `NWConnection` QUIC |
| JS / wasmJs | — | Throws `UnsupportedOperationException` (no raw UDP) |

## The Shape of the API

Everything runs inside a scope that owns the connection's lifetime. The handshake completes before
your block runs, and the connection closes when the block exits — on normal return, exception, or
cancellation.

```kotlin
withQuicConnection("example.com", 443, QuicOptions(alpnProtocols = listOf("h3"))) {
    // `this` is a QuicScope — open streams, send datagrams, migrate paths…
    val stream = openStream()
    // …
    stream.close()
} // connection closed here
```

`QuicOptions.alpnProtocols` is the one required field — QUIC always negotiates an application
protocol during the TLS handshake.

## Next Steps

- [Connections & Streams](./connection) — client, server, the `QuicByteStream` lifecycle, and datagrams
- [Typed Stream Multiplexing](./stream-mux) — exchange codec-typed messages with `withQuicMux`
- [HTTP/3 & WebTransport](../http3/intro) — the `socket-http3` layer built on top of QUIC
