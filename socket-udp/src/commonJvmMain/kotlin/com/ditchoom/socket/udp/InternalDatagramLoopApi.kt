package com.ditchoom.socket.udp

/**
 * Marks the datagram event loops ([DatagramEventLoop], [DatagramEventLoopGroup]): machinery the QUIC
 * transport in this library runs its connections on. It is public only because the QUIC module is a
 * separate module; its shape will change without notice, so nothing outside this library may use it.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to the socket library's transports; it changes without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class InternalDatagramLoopApi
