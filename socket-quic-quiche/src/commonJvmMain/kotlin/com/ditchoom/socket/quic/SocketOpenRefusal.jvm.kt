package com.ditchoom.socket.quic

import java.net.SocketException

/** A JDK-opened socket's refusal is a `SocketException`: `BindException`, `NoRouteToHostException`, and the like. */
internal actual fun platformSocketOpenRefusalOf(thrown: Throwable): SocketOpenRefusal =
    if (thrown is SocketException) SocketOpenRefusal.Refused else SocketOpenRefusal.NotARefusal
