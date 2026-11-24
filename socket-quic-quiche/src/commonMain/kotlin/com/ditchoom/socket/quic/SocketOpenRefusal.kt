package com.ditchoom.socket.quic

/** Whether a throwable is the platform refusing to open or bind a socket, which an entry point answers as a value. */
internal sealed interface SocketOpenRefusal {
    data object Refused : SocketOpenRefusal

    data object NotARefusal : SocketOpenRefusal
}

/**
 * [thrown] read as a socket open refusal: qnet's [NativeSocketRefusal] everywhere, and on the JVM the JDK's own
 * `SocketException` (a `BindException` for a port in use, say) from a stack that opens its socket through the JDK.
 */
internal fun socketOpenRefusalOf(thrown: Throwable): SocketOpenRefusal =
    if (thrown is NativeSocketRefusal) SocketOpenRefusal.Refused else platformSocketOpenRefusalOf(thrown)

/** The platform's own socket refusals, beyond qnet's. */
internal expect fun platformSocketOpenRefusalOf(thrown: Throwable): SocketOpenRefusal
