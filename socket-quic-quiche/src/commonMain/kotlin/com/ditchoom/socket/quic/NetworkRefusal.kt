package com.ditchoom.socket.quic

/** Which of Network.framework's error domains a refusal's [NetworkRefusal.code] belongs to. */
internal enum class NetworkErrorDomain {
    /** The code is a POSIX `errno`. */
    Posix,
    Dns,
    Tls,
    Other,

    /** The connection failed, or never became usable, with no error object attached. */
    Unreported,
}

/** Network.framework refused or failed a client's connection before it was ready, with its own error. */
internal class NetworkRefusal(
    val domain: NetworkErrorDomain,
    val code: Int,
) : Exception() {
    override val message: String get() = "$domain $code"
}
