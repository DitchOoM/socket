package com.ditchoom.socket.quic

/** Native sockets open through qnet, whose refusals are [NativeSocketRefusal]. */
internal actual fun platformSocketOpenRefusalOf(thrown: Throwable): SocketOpenRefusal = SocketOpenRefusal.NotARefusal
