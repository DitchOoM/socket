package com.ditchoom.socket.udp

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.nio.channels.ClosedChannelException

/**
 * Maps the [IOException] a failed NIO `DatagramChannel.send`/`write` threw onto the typed
 * [DatagramSendError] set — the JVM/Android counterpart of `sendErrnoToError`, working from what the
 * JDK leaves of the errno (#457).
 *
 * ## What the JDK leaves of the errno
 *
 * NIO never exposes the errno. Two native paths turn it into an exception, and they do it differently:
 *
 * - **`send(buf, target)` on an unconnected channel** → `DatagramChannelImpl.send0`
 *   (jdk21u `src/java.base/unix/native/libnio/ch/DatagramChannelImpl.c:149-161`). `EAGAIN` becomes
 *   the zero return the send loop absorbs; `ECONNREFUSED` becomes [PortUnreachableException] (which
 *   `DatagramChannelImpl.sendFromNativeBuffer` then swallows unless the channel is connected —
 *   `DatagramChannelImpl.java:1007-1010` — so an unconnected socket never reports it, exactly as the OS
 *   does not); everything else goes through `Net.c handleSocketErrorWithMessage` (`Net.c:125-163`):
 *   `EHOSTUNREACH` → [NoRouteToHostException]; `EADDRINUSE`/`EADDRNOTAVAIL`/`EACCES` → `BindException`
 *   (yes, on a send); `ECONNREFUSED`/`ETIMEDOUT`/`ENOTCONN` → `ConnectException`; every other errno →
 *   `SocketException`. The message of each is `strerror(errno)` and nothing else
 *   (`jni_util.c:108-123` `JNU_ThrowByNameWithLastError`, `jni_util_md.c:64-70` `getLastErrorString`).
 * - **`write(buf)` on a connected channel** — and `send` to the connected peer, which JDK 21 routes the
 *   same way (`DatagramChannelImpl.java:865`) — → `DatagramDispatcher.write0`
 *   (`DatagramDispatcher.c:81-92`): `ECONNREFUSED` → [PortUnreachableException]; everything else →
 *   `IOUtil.c convertReturnVal` (`IOUtil.c:203-222`), which throws a **bare** [IOException] whose message
 *   is again `strerror(errno)`. So `EHOSTUNREACH` on this path is `IOException("No route to host")`,
 *   with no dedicated type at all. Measured on JDK 21 / macOS: a connected write to `240.0.0.1` is
 *   `SocketException("Network is unreachable")` or `IOException("Network is unreachable")` depending on
 *   which native path the JDK took, and `NoRouteToHostException` becomes `IOException("No route to host")`
 *   the moment the channel is connected.
 *
 * Android's `sun.nio.ch.DatagramChannelImpl` is the same code. Its `sendFromNativeBuffer` calls its own
 * native `send0` (libcore `ojluni/src/main/java/sun/nio/ch/DatagramChannelImpl.java:565`,
 * `ojluni/src/main/native/DatagramChannelImpl.c:262-275`), `write` goes through
 * `ojluni/src/main/native/DatagramDispatcher.c:98-109` and `IOUtil.c convertReturnVal`, and
 * `handleSocketError` (`ojluni/src/main/native/Net.c:825-856`) carries the same errno → type table. The
 * `libcore.io.IoBridge.sendto` path (`luni/src/main/java/libcore/io/IoBridge.java:694-720`) — which
 * attaches an `android.system.ErrnoException` cause and phrases the message as
 * `"sendto failed: ENETUNREACH (Network is unreachable)"` (`ErrnoException.java:59-65`) — serves
 * `java.net.DatagramSocket`, not NIO. The phrase match below is a `contains` so that phrasing lands on
 * the same member should a device ever route an NIO send through it.
 *
 * TODO(#457): what a *dying interface* raises on Android mid-send is not yet measured — `ENETUNREACH`
 * once the kernel drops the interface's routes is the expected shape, `ECONNABORTED` (which #396 saw
 * on the receive side when a network was torn down) is the other candidate and is deliberately left
 * as [DatagramSendError.Transport] until a device says which. The measurement to run with the handoff
 * rig is spelled out on the issue.
 *
 * ## Type first, message second
 *
 * The dedicated types are matched first because they are unambiguous. The message is consulted only
 * for the two generic carriers, and only for phrases that are one errno's `strerror` on Darwin, glibc
 * and bionic alike. That is a conversion at the platform boundary — the string dies here and a typed
 * value leaves — not the catch-and-parse in a consumer that the sealed set exists to remove. Anything
 * unrecognized keeps the JDK exception as [DatagramSendError.Transport]'s cause: with no errno to
 * carry, `Transport` is this backend's `OsError`.
 *
 * [attempted] and [limit] are only consulted for `EMSGSIZE`, as in `sendErrnoToError`.
 */
internal fun jvmSendErrorOf(
    e: IOException,
    attempted: Int,
    limit: Int,
): DatagramSendError =
    when (e) {
        // ECONNREFUSED: an earlier datagram to this peer drew an ICMP port unreachable. Both native
        // paths name it (DatagramChannelImpl.c:156-159, DatagramDispatcher.c:87-90).
        is PortUnreachableException -> DatagramSendError.PortUnreachable
        // EHOSTUNREACH on the send0 path (Net.c:143-145). The write0 path has no type for it and is
        // caught by phrase below.
        is NoRouteToHostException -> DatagramSendError.Unreachable(ERRNO_NOT_SURFACED)
        // A close racing the send (ensureOpen / beginWrite), including AsynchronousCloseException and
        // ClosedByInterruptException: the JVM's own type is the detail, the same shape the readiness
        // wait reports when the channel goes away under a parked send.
        is ClosedChannelException -> DatagramSendError.Transport(e)
        else -> byStrerrorPhrase(e, attempted, limit)
    }

/**
 * The generic carriers — `SocketException`/`BindException` from `send0`, a bare `IOException` from
 * `write0` — whose message is `strerror(errno)`. Each phrase is one errno on every libc this backend
 * runs on; a phrase that differs between them is matched on its common prefix.
 */
private fun byStrerrorPhrase(
    e: IOException,
    attempted: Int,
    limit: Int,
): DatagramSendError {
    val message = e.message ?: return DatagramSendError.Transport(e)
    return when {
        UNREACHABLE_PHRASES.any { message.contains(it) } -> DatagramSendError.Unreachable(ERRNO_NOT_SURFACED)
        // EMSGSIZE below the parity guard: an interface MTU with DF set, or a small SO_SNDBUF.
        message.contains("Message too long") -> DatagramSendError.TooLarge(attempted, limit)
        // ENOBUFS: the interface queue is full right now — transient, the same class as EAGAIN on K/N.
        // Reported rather than retried on readiness: OP_WRITE keys on the socket buffer, which is not
        // what is full, so a readiness-driven retry would spin for the whole budget.
        message.contains("No buffer space available") -> DatagramSendError.WouldBlock
        // EACCES: a broadcast destination without SO_BROADCAST. BindException on send0 (Net.c:146-150
        // files EACCES with the bind errors), a bare IOException on write0.
        message.contains("Permission denied") -> DatagramSendError.NotPermitted(ERRNO_NOT_SURFACED)
        else -> DatagramSendError.Transport(e)
    }
}

/**
 * The `strerror` text of every errno `sendErrnoToError` maps to [DatagramSendError.Unreachable], as
 * Darwin, glibc and bionic render it.
 */
private val UNREACHABLE_PHRASES =
    listOf(
        // EHOSTUNREACH — on the write0 path only; send0 names it NoRouteToHostException.
        "No route to host",
        // ENETUNREACH — no route at all, or the interface that held the route is gone.
        "Network is unreachable",
        // ENETDOWN — the interface itself is down.
        "Network is down",
        // EHOSTDOWN — the next hop stopped answering neighbour discovery.
        "Host is down",
        // EAFNOSUPPORT — "…by protocol family" on Darwin, "…by protocol" on glibc and bionic.
        "Address family not supported",
    )
