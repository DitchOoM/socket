package com.ditchoom.socket

actual fun networkCapabilities(): NetworkCapabilities =
    NetworkCapabilities(
        setOf(
            TransportKind.TCP,
            TransportKind.QUIC,
            TransportKind.WEB_TRANSPORT,
            TransportKind.WEB_SOCKET,
        ),
    )
