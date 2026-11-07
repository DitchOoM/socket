package com.ditchoom.socket.quic

/**
 * Marks a declaration only a QUIC engine module may use: public because the engine lives in another
 * module, never something an application calls. Using one outside an engine compiles only with an
 * explicit opt-in, which is the point — an application that builds what only an engine knows (such as a
 * [QuicStreamOrigin.Early] origin) would be claiming a fact about a handshake it cannot see.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Only a QUIC engine builds this; an application has nothing truthful to pass it.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class QuicEngineSpi
