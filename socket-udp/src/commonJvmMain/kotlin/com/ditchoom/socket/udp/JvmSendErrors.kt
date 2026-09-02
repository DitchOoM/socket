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
 * NIO never exposes the errno. Two native paths turn it into an exception, and they do it differently.
 * Line numbers below are jdk21u at tag `jdk-21.0.9-ga` and AOSP libcore at `android-14.0.0_r1`.
 *
 * - **`send(buf, target)` on an unconnected channel** → `DatagramChannelImpl.send0`
 *   (`src/java.base/unix/native/libnio/ch/DatagramChannelImpl.c:149-161`). `EAGAIN`/`EWOULDBLOCK`
 *   become the zero return the send loop absorbs; `EINTR` becomes a retry; `ECONNREFUSED` becomes
 *   [PortUnreachableException] (`:156-159`) — which `DatagramChannelImpl.sendFromNativeBuffer` then
 *   swallows unless the channel is connected (`DatagramChannelImpl.java:1007-1010`), so an unconnected
 *   socket never reports it, exactly as the OS does not; everything else goes through
 *   `Net.c handleSocketErrorWithMessage` (`Net.c:125-161`): `EHOSTUNREACH` → [NoRouteToHostException]
 *   (`:143-145`); `EADDRINUSE`/`EADDRNOTAVAIL`/`EACCES` → `BindException` (`:146-150` — yes, on a send);
 *   `ECONNREFUSED`/`ETIMEDOUT`/`ENOTCONN` → `ConnectException` (`:137-141`); every other errno →
 *   `SocketException`. The detail of each is `strerror(errno)` and nothing else, because
 *   `JNU_ThrowByNameWithLastError` uses `getLastErrorString` as the *whole* message and only falls back
 *   to the caller's default when there is none (`jni_util.c:107-123`, `jni_util_md.c getLastErrorString`).
 * - **`write(buf)` on a connected channel** — and `send` to the connected peer, which JDK 21 routes the
 *   same way (`DatagramChannelImpl.java:865`) — → `DatagramDispatcher.write0`
 *   (`DatagramDispatcher.c:81-92`): `ECONNREFUSED` → [PortUnreachableException] (`:87-90`); everything
 *   else → `IOUtil.c convertReturnVal` (`IOUtil.c:202-222`), which calls
 *   `JNU_ThrowIOExceptionWithLastError` (`jni_util.c:183`) and so throws a **bare** [IOException] whose
 *   message is again `strerror(errno)`. There is no dedicated type on this path at all.
 *
 * Measured on this repo's JDK 21.0.9 / macOS 26.6.2 aarch64, driving `java.nio.channels.DatagramChannel`
 * directly (the probe is in the PR description, and it is reproducible on any host with a v6 loopback,
 * because a socket bound to `::1` has no route to a global v6 address whatever the default route does):
 *
 * ```
 * bound ::1  -> 2001:db8::1  unconnected send : java.net.NoRouteToHostException("No route to host")
 * bound ::1  -> 2001:db8::1  connected write  : java.io.IOException("No route to host")     <- bare
 * connected loopback, closed port, 2nd send   : java.net.PortUnreachableException(null)     <- no message
 * 60000B with SO_SNDBUF=2048, unconnected     : java.net.SocketException("Message too long")
 * 60000B with SO_SNDBUF=2048, connected       : java.io.IOException("Message too long")     <- bare
 * 255.255.255.255 w/o SO_BROADCAST, unconn.   : java.net.BindException("Permission denied")
 * 255.255.255.255 w/o SO_BROADCAST, connected : java.io.IOException("Permission denied")    <- bare
 * bound 127.0.0.1 -> 1.1.1.1 (EADDRNOTAVAIL)  : java.net.BindException("Can't assign requested address")
 * ```
 *
 * The second line is why the type check alone is not enough: the case that matters to a QUIC connection
 * — a *connected* socket that can no longer reach its peer — has no type, only a phrase. And the third
 * is why the phrase alone is not enough: [PortUnreachableException] carries no message
 * (`JNU_ThrowByName(env, ..., 0)`).
 *
 * Android's `sun.nio.ch.DatagramChannelImpl` is the same code with one difference that matters here.
 * `sendFromNativeBuffer` calls its own native `send0` (libcore
 * `ojluni/src/main/java/sun/nio/ch/DatagramChannelImpl.java:565`,
 * `ojluni/src/main/native/DatagramChannelImpl.c:262-275`) and `write` goes through
 * `ojluni/src/main/native/DatagramDispatcher.c:98-109` into the same `IOUtil.c convertReturnVal`; both
 * name `ECONNREFUSED` as [PortUnreachableException] as the JDK does. But libcore's
 * `handleSocketErrorWithDefault` (`ojluni/src/main/native/Net.c:823-853`) has **no `EACCES` case** — it
 * files only `EADDRINUSE`/`EADDRNOTAVAIL` under `BindException`, so `EACCES` falls to the default and
 * arrives as a plain `SocketException("Permission denied")` where the JDK gives a `BindException`. The
 * phrase match below is what keeps those two on the same member. `EHOSTUNREACH` →
 * `NoRouteToHostException` is present in libcore too, unchanged.
 *
 * The `libcore.io.IoBridge.sendto` path — which attaches an `android.system.ErrnoException` cause and
 * phrases the message as `"sendto failed: ENETUNREACH (Network is unreachable)"`
 * (`ErrnoException.java:59-65`) — serves `java.net.DatagramSocket`, not NIO. The phrase match below is a
 * `contains` so that phrasing lands on the same member should a device ever route an NIO send through it.
 *
 * TODO(#457): what a *dying interface* raises on Android mid-send is not yet measured — no desk can
 * produce it, and the phone was unavailable when this landed. `ENETUNREACH` once the kernel drops the
 * interface's routes is the expected shape and is mapped; `ENETDOWN` and `EHOSTUNREACH` are mapped for
 * the same reason. `ECONNABORTED` (which #396 saw on the *receive* side when a network was torn down)
 * is the other candidate and is deliberately left as [DatagramSendError.Transport] until a device says
 * so, because guessing it into [DatagramSendError.Unreachable] would be a migration trigger firing on
 * evidence nobody collected. The exact measurement to run with the handoff rig is on the issue.
 *
 * ## Type first, message second
 *
 * The dedicated types are matched first because they are unambiguous, and because two of them carry no
 * message at all. The message is consulted only for the generic carriers — `SocketException` and
 * `BindException` from `send0`, a bare [IOException] from `write0` — and only for phrases that are one
 * errno's `strerror` on Darwin, glibc and bionic alike. That is a conversion at the platform boundary —
 * the string dies here and a typed value leaves — not the catch-and-parse in a consumer that the sealed
 * set exists to remove. #457's proposed shape was types only; measurement showed types only cannot reach
 * the connected path, which is the path a QUIC connection uses.
 *
 * Anything unrecognized keeps the JDK exception as [DatagramSendError.Transport]'s cause — including
 * `ConnectException`, which on a send can only mean `ETIMEDOUT` or `ENOTCONN` (the `ECONNREFUSED` arm
 * never reaches `Net.c`), neither of which is a statement about the path. With no errno to carry,
 * `Transport` is this backend's `OsError`.
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
        // paths name it (DatagramChannelImpl.c:156-159, DatagramDispatcher.c:87-90) and both throw it
        // with a null message, so only the type can carry it.
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
        // EACCES: a broadcast destination without SO_BROADCAST. BindException on the JDK's send0
        // (Net.c:146-150 files EACCES with the bind errors), a plain SocketException on Android's
        // (libcore Net.c:823-853 has no EACCES case), a bare IOException on write0 — one member for
        // all three.
        message.contains("Permission denied") -> DatagramSendError.NotPermitted(ERRNO_NOT_SURFACED)
        else -> DatagramSendError.Transport(e)
    }
}

/**
 * The `strerror` text of every errno `sendErrnoToError` maps to [DatagramSendError.Unreachable], as
 * Darwin, glibc and bionic render it.
 */
internal val UNREACHABLE_PHRASES =
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
