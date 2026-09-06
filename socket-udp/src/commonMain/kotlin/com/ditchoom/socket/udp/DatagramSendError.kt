package com.ditchoom.socket.udp

/**
 * Why a datagram send failed, as a concrete type rather than a parsed message.
 *
 * Modelled as the *reason* rather than as an exception hierarchy so the same values can be carried by
 * a future non-throwing `trySend` outcome without redefining anything: consumers that want to branch
 * (ICE marking a candidate pair unusable) and consumers that want to unwind (quiche tearing the
 * connection down) read the same sealed set.
 *
 * The split that matters is transient vs terminal. [WouldBlock] is the only transient member — the
 * socket could not accept the datagram *yet* — and backends absorb it internally rather than
 * surfacing it, retrying within a send budget so that momentary local buffer pressure never reaches
 * a caller as a failure. It reaches one only when a backend gave up waiting, which is a genuine
 * failure to transmit.
 *
 * That absorption originally had a second motive: `QuicheDriver.flushOutgoing` treated *any* send
 * exception as fatal and would end a live QUIC connection over a full send buffer. **That is no
 * longer true** — `flushOutgoing` now branches on a typed `SendOutcome` and a failed send stops the
 * flush without terminating the connection, because RFC 9000 §10 does not list a failed local send
 * among the ways a connection ends. The absorption stays regardless, on its own merits: a discarded
 * zero-length write would count as transmitted and inflate quiche's bytes-in-flight, surfacing later
 * as spurious loss detection.
 *
 * Numeric codes are kept in their own namespaces on purpose. An `errno` and a Network.framework
 * `(domain, code)` are not the same kind of number, so flattening them into one field would make the
 * value lie about what it means.
 *
 * ## Where each member is constructed
 *
 * A member a consumer can branch on is only worth branching on if every backend constructs it for
 * the same condition. A backend that *cannot* produce a member says so here rather than leaving a
 * consumer to discover it on one platform (#457 was exactly that: [Unreachable] was unconstructible
 * on JVM/Android, so a migration trigger wired to it could never fire there).
 *
 * Five backends send, and three of them share one classifier:
 *
 * - **Linux io_uring** — `IoUringDatagramChannel`, through `sendErrnoToError(-res)`.
 * - **Apple POSIX** — `PosixUdpDatagramChannel`, through `sendErrnoToError(errno)`.
 * - **Apple Network.framework** — `NwUdpDatagramChannel`: the POSIX error domain through
 *   `sendErrnoToError`, every other domain as [PlatformError].
 * - **JVM / Android NIO** — `NioDatagramChannel`, through `jvmSendErrorOf`, which has no errno to read
 *   and works from the JDK's exception type and its `strerror` message instead.
 * - **Node** — `NodeDatagramChannel`, through `nodeSendError`, which reads the errno's *name*.
 *
 * What each member means on each of them:
 *
 * - [TooLarge] — the oversize parity guard on all five, and additionally `EMSGSIZE` (POSIX, Node) or
 *   `"Message too long"` (JVM/Android) when the interface MTU refuses a datagram the guard allowed.
 * - [Unreachable] — `EHOSTUNREACH`, `ENETUNREACH`, `ENETDOWN`, `EHOSTDOWN`, `EAFNOSUPPORT`; the same
 *   five errno *names* on Node; on JVM/Android `NoRouteToHostException` plus those five errnos'
 *   `strerror` text, which is all the JDK leaves of them.
 * - [PortUnreachable] — `ECONNREFUSED` everywhere; `PortUnreachableException` on JVM/Android.
 * - [NotPermitted] — `EACCES`; `"Permission denied"` on JVM/Android.
 * - [SourceAddressUnavailable] — **the Linux io_uring backend only**, the one that reads
 *   `DatagramSendOptions.fromLocal`. Two of its reasons are decided before the syscall (a source in
 *   the wrong address family, an unscoped IPv6 link-local) and two after one failed (an address no
 *   interface holds, or a failure this backend could not attribute). Unconstructible on the other
 *   four *by design* — a backend advertising `DatagramCapabilities.sourceAddressSelect = false` never
 *   reads `fromLocal` at all, so it has nothing to refuse — rather than by the omission #457 recorded.
 * - [WouldBlock] — `EAGAIN`, `EWOULDBLOCK`, `ENOBUFS`; on JVM/Android the send budget running out, and
 *   `"No buffer space available"`.
 * - [OsError] — every other errno on the three POSIX backends. **Never on JVM/Android or Node**: NIO
 *   and Node never surface an errno, so [Transport] is their raw member instead.
 * - [PlatformError] — Apple Network.framework's non-POSIX domains (dns, tls) only. Never elsewhere.
 * - [Transport] — every unclassified `IOException` (JVM/Android) or `Error` (Node), kept as the cause.
 *   **Never on the three POSIX backends**, which always have an errno and report [OsError].
 */
sealed interface DatagramSendError {
    /** The payload exceeded what this socket can transmit in one datagram (`EMSGSIZE`). */
    data class TooLarge(
        val attempted: Int,
        val limit: Int,
    ) : DatagramSendError

    /**
     * This socket cannot get a datagram onto a path to the destination: `EHOSTUNREACH`, `ENETUNREACH`,
     * `ENETDOWN`, `EHOSTDOWN`, `EAFNOSUPPORT`.
     *
     * A *local* verdict — no route, the interface is gone, the next hop is dead — which is the signal
     * a migration trigger or an ICE agent branches on. Distinct from [PortUnreachable], where a path
     * exists and the peer host answered on it.
     *
     * [errno] is the platform's code where the runtime surfaces one (the POSIX backends, and Apple's
     * Network.framework POSIX domain) and [ERRNO_NOT_SURFACED] where it does not: JVM/Android NIO
     * reduces the errno to an exception type or its `strerror` text before this library sees it, and
     * Node reports a name. The member is the contract; the number is a diagnostic for the backends
     * that have one.
     */
    data class Unreachable(
        val errno: Int,
    ) : DatagramSendError

    /**
     * The peer host answered an earlier datagram to this destination with ICMP port unreachable — a
     * path exists and something at the far end said nothing listens there (`ECONNREFUSED` on a
     * connected socket; the JVM's `PortUnreachableException`).
     *
     * Its own member, not a case of [Unreachable], because a consumer acts on them oppositely: an ICE
     * agent fails the candidate pair either way, but a QUIC connection must **not** migrate on it —
     * the local path is fine, the peer is not there, and no other path would change that.
     *
     * Only a *connected* socket learns of it: the kernel attributes the ICMP reply to the socket by
     * the 4-tuple, which an unconnected socket does not have. On such a socket the JDK swallows the
     * exception and the send reports success, exactly as the OS does for a bare `sendto`.
     */
    data object PortUnreachable : DatagramSendError

    /**
     * Refused by policy — e.g. a broadcast destination without `SO_BROADCAST` (`EACCES`). [errno] is
     * [ERRNO_NOT_SURFACED] on the backends that do not expose one; see [Unreachable].
     */
    data class NotPermitted(
        val errno: Int,
    ) : DatagramSendError

    /**
     * The send named a source address through `DatagramSendOptions.fromLocal` that this socket could
     * not leave from, so nothing was transmitted.
     *
     * Its own member because both alternatives lie. The kernel has no errno meaning "that source is
     * not mine" — Linux answers a non-local IPv4 `IP_PKTINFO` with `ENETUNREACH` and a non-local IPv6
     * `IPV6_PKTINFO` with `EINVAL` — so passing the raw code through would tell a consumer the
     * *destination* is unreachable, which is exactly the signal a migration trigger branches on, or
     * bury the reason in [OsError]. Quietly sending unpinned instead would be worse still: #556 exists
     * because a reply leaving from the wrong local address is dropped by a `connect()`ed client, so an
     * unnoticed fallback to the kernel's choice reintroduces the defect the caller asked to prevent.
     *
     * Nothing was sent. A caller that would rather transmit from any source than not at all retries
     * without `fromLocal`; that choice is the caller's, which is the point of reporting it.
     *
     * A **wildcard** source is not this. `0.0.0.0` / `::` name no address, so they are read as the
     * absence of a request and the platform chooses, exactly as an unset `fromLocal` does — a decision,
     * not a silent no-op reported as an honoured request.
     */
    data class SourceAddressUnavailable(
        /** The numeric host of the refused `fromLocal` — a diagnostic. Branch on [reason]. */
        val requestedHost: String,
        val reason: SourceAddressRejection,
    ) : DatagramSendError

    /** The socket could not accept the datagram before the backend stopped waiting. Transient. */
    data object WouldBlock : DatagramSendError

    /**
     * Any other POSIX failure, carrying the raw `errno` rather than a rendered message. Never
     * constructed on JVM/Android or Node, which have no errno to carry — their raw member is
     * [Transport].
     */
    data class OsError(
        val errno: Int,
    ) : DatagramSendError

    /** A failure reported in a platform's own namespace: Network.framework's `(domain, code)`. */
    data class PlatformError(
        val domain: Int,
        val code: Int,
    ) : DatagramSendError

    /**
     * The underlying transport threw something this library does not classify, and the exception owns
     * the detail (a JVM `IOException`, a Node `Error`). On JVM/Android this includes a close racing the
     * send (`ClosedChannelException` and its subtypes) — the JDK's own type is the detail there.
     */
    data class Transport(
        val cause: Throwable,
    ) : DatagramSendError

    /** Human-readable rendering. The structured value stays this sealed type; this is only display. */
    fun describe(): String =
        when (this) {
            is TooLarge -> "payload of $attempted bytes exceeds the $limit byte send limit"
            is Unreachable -> "destination unreachable${errnoSuffix(errno)}"
            is PortUnreachable -> "peer answered ICMP port unreachable"
            is NotPermitted -> "send not permitted${errnoSuffix(errno)}"
            is SourceAddressUnavailable -> "cannot send from '$requestedHost': ${reason.describe()}"
            is WouldBlock -> "socket could not accept the datagram before the send deadline"
            is OsError -> "send failed (errno=$errno)"
            is PlatformError -> "send failed (domain=$domain, code=$code)"
            is Transport -> "send failed: $cause"
        }
}

/**
 * Why a source address a send named could not be used — the reason inside
 * [DatagramSendError.SourceAddressUnavailable].
 *
 * Two members, told apart by *who* refused: this library, before any syscall, or the kernel
 * afterwards. They stay apart because only one of them can be caused by the host changing under a
 * running process. An interface losing its address is a fact about the network and can happen to a
 * server that was correct a second ago; a source in the wrong address family could never have worked
 * and is a caller naming something impossible.
 */
sealed interface SourceAddressRejection {
    /**
     * The named source is not in this socket's address family — an IPv4 literal on an `AF_INET6`
     * socket, or an IPv6 one on `AF_INET`.
     *
     * Refused here rather than handed to the kernel, because the kernel does **not** refuse it: an
     * `IPV6_PKTINFO` control message on an `AF_INET` socket is silently skipped and the datagram
     * leaves from whichever address routing preferred (measured, Linux 6.18). Passing it down would
     * therefore be indistinguishable, from the caller's side, from having honoured the request — the
     * precise shape of the #556 defect.
     */
    data object WrongFamily : SourceAddressRejection

    /**
     * An IPv6 link-local source (`fe80::/10`) that carries no interface index.
     *
     * `fe80::1` is not one address, it is one address *per link* — the same bits name a different host
     * on every interface a machine has — so the kernel refuses to send from one, or even to bind one,
     * without being told which (`EINVAL` from both, measured). Its own member rather than [NotAssigned]
     * because that would state the opposite of the truth: the host may hold this address perfectly
     * well, and what is missing is the scope, which the caller can supply.
     */
    data object UnscopedLinkLocal : SourceAddressRejection

    /**
     * No interface on this host holds the named address, so the kernel could not build a route out of
     * it. [errno] is what `sendmsg` reported — `ENETUNREACH` for IPv4, `EINVAL` for IPv6 on Linux —
     * and is a diagnostic only: the member is the contract, the number is not.
     *
     * Only stated when the backend positively established it. When it could not, the answer is
     * [Undetermined] rather than this one on the balance of probability.
     */
    data class NotAssigned(
        val errno: Int,
    ) : SourceAddressRejection

    /**
     * The kernel refused a send that named this source, and the backend could not establish whether
     * the source is why: [sendErrno] is what `sendmsg` said, [probeErrno] why the locality check could
     * not answer (the process out of descriptors, ephemeral ports exhausted, a scope the probe could
     * not bind).
     *
     * Exists so that "I do not know" has somewhere to go. Folding it into [NotAssigned] would publish
     * a claim about the host that was never checked, and folding it into the raw errno would publish
     * [DatagramSendError.Unreachable] — the verdict a migration trigger and an ICE agent branch on —
     * about a peer that may be perfectly reachable. Both were real defects here; this member is what
     * removed the need to choose between them.
     */
    data class Undetermined(
        val sendErrno: Int,
        val probeErrno: Int,
    ) : SourceAddressRejection

    /** Human-readable rendering, for [DatagramSendError.describe]. Display only. */
    fun describe(): String =
        when (this) {
            WrongFamily -> "it is not this socket's address family"
            UnscopedLinkLocal -> "an IPv6 link-local source needs the interface it is scoped to"
            is NotAssigned -> "no interface on this host holds it (errno=$errno)"
            is Undetermined -> "the send failed (errno=$sendErrno) and the source could not be checked (errno=$probeErrno)"
        }
}

/**
 * The value [DatagramSendError.Unreachable.errno] and [DatagramSendError.NotPermitted.errno] carry on
 * a backend whose runtime never surfaced an errno — JVM/Android NIO, which reduces it to an exception
 * type or a `strerror` message before this library sees it, and Node, which reports a name.
 *
 * Zero is not an errno on any platform this library runs on — POSIX numbers them from 1 — so it cannot
 * be mistaken for a real code. It is public because it is observable: a consumer reading `errno` off a
 * member needs to be able to tell "the platform said `EHOSTUNREACH`" from "the platform did not say".
 *
 * The member is the contract, and it is the same on every backend. The number is a diagnostic for the
 * three backends that have one, and is never the thing to branch on.
 */
const val ERRNO_NOT_SURFACED = 0

private fun errnoSuffix(errno: Int): String = if (errno == ERRNO_NOT_SURFACED) "" else " (errno=$errno)"

/**
 * Thrown when a send could not transmit. Carries the typed [error]; catch-and-inspect rather than
 * catch-and-parse.
 *
 * This is the reporting half of the module's send contract: **a send either delivers or reports, and
 * never returns normally having sent nothing.** Four of five backends used to discard their send
 * result, so a datagram could vanish between a clean return and the wire — invisible to a caller and,
 * for quiche, a lie to its congestion controller.
 */
class DatagramSendException(
    val error: DatagramSendError,
) : RuntimeException(error.describe(), (error as? DatagramSendError.Transport)?.cause)
