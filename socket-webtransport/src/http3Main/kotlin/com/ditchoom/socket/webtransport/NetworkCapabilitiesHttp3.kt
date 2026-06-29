package com.ditchoom.socket.webtransport

/** jvm/android/native have raw sockets + QUIC on the classpath, so all four transports are present. */
actual fun networkCapabilities(): NetworkCapabilities =
    NetworkCapabilities(
        setOf(
            TransportKind.TCP,
            TransportKind.QUIC,
            TransportKind.WEB_TRANSPORT,
            TransportKind.WEB_SOCKET,
        ),
    )
