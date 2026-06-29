package com.ditchoom.socket.webtransport

/** The browser has no raw TCP/QUIC on the classpath — only WebTransport and WebSocket. */
actual fun networkCapabilities(): NetworkCapabilities =
    NetworkCapabilities(
        setOf(
            TransportKind.WEB_TRANSPORT,
            TransportKind.WEB_SOCKET,
        ),
    )
