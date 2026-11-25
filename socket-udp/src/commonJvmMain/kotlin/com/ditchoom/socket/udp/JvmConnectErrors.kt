package com.ditchoom.socket.udp

import java.io.IOException
import java.net.BindException
import java.net.NoRouteToHostException

/**
 * Classify a `java.net` failure raised on the way to a connected UDP socket — `open`, `bind` or
 * `connect` — onto [UdpConnectError]. The connect-side twin of [jvmSendErrorOf].
 *
 * The JDK reduces the errno to a type where it has one and to its rendering of the errno where it
 * does not (`Net.c handleSocketErrorWithMessage`, Windows `NET_ThrowNew`): `EADDRINUSE`,
 * `EADDRNOTAVAIL` and `EACCES` → [BindException] from **both** `bind0` and `connect0` (a 4-tuple
 * collision on connect is reported as a bind failure), `EHOSTUNREACH` → [NoRouteToHostException], and
 * everything else a `SocketException` carrying the phrase. Each phrase renders exactly one errno, so
 * the phrase decides the member and [host] gives back that errno's number. Anything unrecognised keeps
 * the exception as [UdpConnectError.Transport]'s cause rather than being flattened into a message.
 */
internal fun jvmConnectErrorOf(
    e: IOException,
    host: HostErrnos = HostErrnos.current,
): UdpConnectError {
    val phrase = if (e is NoRouteToHostException) JdkErrnoPhrase.HostUnreachable else JdkErrnoPhrase.of(e)
    val errno = host.errnoOf(phrase)
    return when (phrase.kind) {
        ErrnoKind.AddressInUse -> UdpConnectError.AddressInUse(errno)
        ErrnoKind.AddressUnavailable -> UdpConnectError.LocalAddressUnavailable(errno)
        ErrnoKind.Unreachable -> UdpConnectError.Unreachable(errno)
        ErrnoKind.NotPermitted -> UdpConnectError.NotPermitted(errno)
        ErrnoKind.OutOfSockets -> UdpConnectError.SocketUnavailable(errno)
        // A BindException whose phrase is none of the above is still, by type, the endpoint being
        // taken: the most common of the three errnos the JDK files under it.
        ErrnoKind.Unrecognised ->
            if (e is BindException) UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED) else UdpConnectError.Transport(e)
    }
}

/**
 * Classify a `java.net` failure raised by a bind — `open`, `bind` or `getsockname` — onto
 * [UdpBindError]. The same phrase table as [jvmConnectErrorOf]: the JDK's [BindException] carries
 * `EADDRINUSE`, `EADDRNOTAVAIL` and `EACCES` alike, so its type alone cannot say whether another port
 * would help.
 */
internal fun jvmBindErrorOf(
    e: IOException,
    host: HostErrnos = HostErrnos.current,
): UdpBindError {
    val phrase = JdkErrnoPhrase.of(e)
    val errno = host.errnoOf(phrase)
    return when (phrase.kind) {
        ErrnoKind.AddressInUse -> UdpBindError.AddressInUse(errno)
        ErrnoKind.AddressUnavailable -> UdpBindError.LocalAddressUnavailable(errno)
        ErrnoKind.NotPermitted -> UdpBindError.NotPermitted(errno)
        ErrnoKind.OutOfSockets -> UdpBindError.SocketUnavailable(errno)
        ErrnoKind.Unreachable, ErrnoKind.Unrecognised -> UdpBindError.Transport(e)
    }
}

/** Which sealed member a [JdkErrnoPhrase] belongs to. */
internal enum class ErrnoKind { AddressInUse, AddressUnavailable, Unreachable, NotPermitted, OutOfSockets, Unrecognised }

/**
 * The errnos the connect and bind classifiers recognise, each with the phrase the JDK renders it as.
 * Matched in declaration order, so a phrase that contains another ("Too many open files in system")
 * comes before it.
 */
internal enum class JdkErrnoPhrase(
    val kind: ErrnoKind,
    private val phrase: String,
) {
    AddressInUse(ErrnoKind.AddressInUse, "Address already in use"),

    /** "Can't assign requested address" on Darwin, "Cannot assign …" on glibc, bionic and Windows. */
    AddressNotAvailable(ErrnoKind.AddressUnavailable, "assign requested address"),
    HostUnreachable(ErrnoKind.Unreachable, "No route to host"),
    NetworkUnreachable(ErrnoKind.Unreachable, "Network is unreachable"),
    NetworkDown(ErrnoKind.Unreachable, "Network is down"),
    HostDown(ErrnoKind.Unreachable, "Host is down"),

    /** "…by protocol family" on Darwin and Windows, "…by protocol" on glibc and bionic. */
    AddressFamilyNotSupported(ErrnoKind.Unreachable, "Address family not supported"),
    AccessDenied(ErrnoKind.NotPermitted, "Permission denied"),
    OperationNotPermitted(ErrnoKind.NotPermitted, "Operation not permitted"),
    SystemFileTableFull(ErrnoKind.OutOfSockets, "Too many open files in system"),
    ProcessFileTableFull(ErrnoKind.OutOfSockets, "Too many open files"),

    /** Windows renders `WSAENOBUFS` as "No buffer space available (maximum connections reached?)". */
    NoBufferSpace(ErrnoKind.OutOfSockets, "No buffer space available"),
    OutOfMemory(ErrnoKind.OutOfSockets, "Cannot allocate memory"),

    /** A message that renders none of the above, or no message at all. */
    Unrecognised(ErrnoKind.Unrecognised, ""),
    ;

    companion object {
        private val recognised = entries - Unrecognised

        /** The errno [e]'s message renders. */
        fun of(e: IOException): JdkErrnoPhrase {
            val message = e.message ?: return Unrecognised
            return recognised.firstOrNull { message.contains(it.phrase) } ?: Unrecognised
        }
    }
}

/**
 * The number this host's kernel gives each [JdkErrnoPhrase]. The JDK hands over the phrase and not
 * the number, and the phrase-to-number map is fixed per OS. Windows has no `EPERM`, `ENFILE` or
 * `ENOMEM` socket error, so those carry [ERRNO_NOT_SURFACED] there, as does every phrase on an OS this
 * table does not know.
 */
internal enum class HostErrnos(
    private val numbers: Map<JdkErrnoPhrase, Int>,
) {
    /** Linux and Android: glibc, musl and bionic share the kernel's numbers. */
    Linux(
        mapOf(
            JdkErrnoPhrase.OperationNotPermitted to 1,
            JdkErrnoPhrase.OutOfMemory to 12,
            JdkErrnoPhrase.AccessDenied to 13,
            JdkErrnoPhrase.SystemFileTableFull to 23,
            JdkErrnoPhrase.ProcessFileTableFull to 24,
            JdkErrnoPhrase.AddressFamilyNotSupported to 97,
            JdkErrnoPhrase.AddressInUse to 98,
            JdkErrnoPhrase.AddressNotAvailable to 99,
            JdkErrnoPhrase.NetworkDown to 100,
            JdkErrnoPhrase.NetworkUnreachable to 101,
            JdkErrnoPhrase.NoBufferSpace to 105,
            JdkErrnoPhrase.HostDown to 112,
            JdkErrnoPhrase.HostUnreachable to 113,
        ),
    ),

    /** macOS and the other Darwin kernels. */
    Darwin(
        mapOf(
            JdkErrnoPhrase.OperationNotPermitted to 1,
            JdkErrnoPhrase.OutOfMemory to 12,
            JdkErrnoPhrase.AccessDenied to 13,
            JdkErrnoPhrase.SystemFileTableFull to 23,
            JdkErrnoPhrase.ProcessFileTableFull to 24,
            JdkErrnoPhrase.AddressFamilyNotSupported to 47,
            JdkErrnoPhrase.AddressInUse to 48,
            JdkErrnoPhrase.AddressNotAvailable to 49,
            JdkErrnoPhrase.NetworkDown to 50,
            JdkErrnoPhrase.NetworkUnreachable to 51,
            JdkErrnoPhrase.NoBufferSpace to 55,
            JdkErrnoPhrase.HostDown to 64,
            JdkErrnoPhrase.HostUnreachable to 65,
        ),
    ),

    /** Winsock's `WSAE*` codes, the numbers `WSAGetLastError` reports. */
    Windows(
        mapOf(
            JdkErrnoPhrase.AccessDenied to 10013,
            JdkErrnoPhrase.ProcessFileTableFull to 10024,
            JdkErrnoPhrase.AddressFamilyNotSupported to 10047,
            JdkErrnoPhrase.AddressInUse to 10048,
            JdkErrnoPhrase.AddressNotAvailable to 10049,
            JdkErrnoPhrase.NetworkDown to 10050,
            JdkErrnoPhrase.NetworkUnreachable to 10051,
            JdkErrnoPhrase.NoBufferSpace to 10055,
            JdkErrnoPhrase.HostDown to 10064,
            JdkErrnoPhrase.HostUnreachable to 10065,
        ),
    ),

    /** An OS this table does not know: every phrase still classifies, with no number. */
    Unknown(emptyMap()),
    ;

    fun errnoOf(phrase: JdkErrnoPhrase): Int = numbers[phrase] ?: ERRNO_NOT_SURFACED

    companion object {
        val current: HostErrnos =
            System.getProperty("os.name").orEmpty().let { os ->
                when {
                    os.startsWith("Linux") -> Linux
                    os.startsWith("Mac") || os.startsWith("Darwin") -> Darwin
                    os.startsWith("Windows") -> Windows
                    else -> Unknown
                }
            }
    }
}
