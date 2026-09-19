@file:OptIn(InternalQuicApi::class)

package com.ditchoom.socket.quic

/**
 * What [UdpSocketChannelFactory.routeSourceAddress] learned about the local address to bind for a new
 * migration path.
 *
 * Three unrelated facts that a bare `String?` would collapse into one `null`: *the platform assigns the
 * endpoint itself* (Apple — where an unnamed bind is the correct and only behaviour), *the probe
 * failed* (fd exhaustion, a sandbox, no route), and *bind the wildcard anyway*. Those three demand
 * opposite handling, so each is a member here.
 *
 * The rule this enforces: **an unnamed bind is only ever taken because a platform said it names the
 * endpoint, never because something failed.**
 */
internal sealed interface RouteSource {
    /**
     * The routing table chooses [host] as the source for the peer, and the new path binds it.
     *
     * [host] names a real interface: the wildcard is rejected as [RouteProbeFailure.SourceAddressUnnamed]
     * before it can get here, so nothing that reads this member has to re-check.
     */
    data class Resolved(
        val host: String,
    ) : RouteSource

    /**
     * The platform assigns the local endpoint itself ([LocalEndpointSupport.PlatformAssigned]), so there
     * is no source address to name and none is asked for. Apple hands the endpoint to `NWConnection`,
     * whose own `UdpSocket.connect` comment calls `localHost`/`localPort` advisory.
     *
     * Emphatically **not** a failure and not a degradation: an unnamed bind is what this platform
     * serves, and it is what automatic migration asks for everywhere. It is a distinct member precisely
     * so that the one legitimate unnamed bind cannot be confused with the illegitimate one.
     */
    data object PlatformAssigned : RouteSource

    /** The probe could not name a source address on a platform that binds one; [reason] says how. */
    data class Unresolved(
        val reason: RouteProbeFailure,
    ) : RouteSource
}

/**
 * Why the route probe could not name the source address for a migration path.
 *
 * Carried by [UnresolvedRouteSourceException] into
 * `MigrationResult.Unmoved.Failed.LocalPathUnavailable(cause)`, so a caller that wants to know *why* a
 * path could not be opened gets a member to inspect rather than a message to parse.
 *
 * **Why "the socket could not be created", "the connect was refused" and "the host has no route" are
 * one member and not three.** They are one member *here* because this layer cannot tell them apart:
 * `UdpSocket.connect` opens, binds and connects in a single call, and the two backends that reach this
 * code do not agree on how they report the refusal. JVM/Android raise typed `java.net` exceptions
 * (`BindException`, `NoRouteToHostException`, a plain `SocketException`); the Linux actual throws one
 * untyped `IllegalStateException("connect to … failed")` with no errno
 * (`socket-udp/src/linuxMain/…/UdpSocket.linux.kt`). Splitting on the JVM's exception classes would
 * mint members Linux can never construct — the defect `DatagramSendError` carries on JVM/Android, not
 * a shape to copy deliberately. The place that can classify these honestly is the `socket-udp` backend
 * that holds the errno; [ProbeRefused] carries the platform's own exception, which is the most
 * specific true thing this seam has.
 *
 * The two members that *are* split out are split because this layer really can tell them apart, and
 * because they mean opposite things about the route: in both of them the probe **connected**, so a
 * route to the peer exists — only the answer is unusable.
 */
@InternalQuicApi
sealed interface RouteProbeFailure {
    /**
     * `UdpSocket.connect` refused the probe: no descriptor left, a sandbox that forbids the socket, a
     * refused bind or connect, or no route to the peer's address. [cause] is the platform's own
     * exception — see this type's KDoc for why it is not pre-classified further.
     */
    data class ProbeRefused(
        val cause: Throwable,
    ) : RouteProbeFailure

    /**
     * The probe connected, but the platform reported [com.ditchoom.buffer.flow.LocalAddress.Unknown] —
     * `getsockname` failed, or the backend does not surface a local endpoint on a connected socket.
     *
     * Both backends that run the probe report it in practice (NIO reads `getLocalAddress`, io_uring
     * `getsockname`), so this is the state that says a platform which advertised
     * [LocalEndpointSupport.Bindable] cannot in fact name what it bound — a contradiction worth a
     * member of its own rather than a silent unnamed bind.
     */
    data object SourceAddressUnknown : RouteProbeFailure

    /**
     * The probe reported [host], which names no interface — the wildcard (`0.0.0.0`, `::`,
     * `0:0:0:0:0:0:0:0`).
     *
     * Binding *that* is the unnamed bind every other member exists to rule out, so accepting it as a
     * resolved answer would restore the defect while looking like the fix. It is reachable only if a
     * platform reports a connected socket's source as the unnamed address; the JVM does not — a
     * wildcard-bound `DatagramChannel` reports `0:0:0:0:0:0:0:0` **before** `connect` and the route's
     * specific address after it (`127.0.0.1` for a v4 peer, `0:0:0:0:0:0:0:1` for `::1`). The member
     * exists because that readback is the one remaining way a wildcard could reach a bind without
     * anything saying so, and it costs one comparison to make it say so.
     */
    data class SourceAddressUnnamed(
        val host: String,
    ) : RouteProbeFailure

    /** Human-readable rendering. The structured value stays this sealed type; this is only display. */
    fun describe(): String =
        when (this) {
            is ProbeRefused -> "the route probe could not be opened: $cause"
            is SourceAddressUnknown -> "the route probe connected but the platform reported no local address"
            is SourceAddressUnnamed -> "the route probe reported the unnamed address ($host), which names no interface"
        }
}

/**
 * Thrown when the route source could not be resolved and the caller named no source of its own — by
 * [UdpChannelFactory.openPath] for a migration path, and by `UdpSocketChannelFactory.openPrimaryChannel`
 * for the socket a connection starts on. Carries the typed
 * [reason]; catch-and-inspect rather than catch-and-parse.
 *
 * **Why this fails the path open instead of falling back to an unnamed bind.** An unnamed bind against
 * the peer every other path on this connection already holds collides on the 4-tuple, so "resolve
 * failed, bind the wildcard anyway" is a silent return to a known-broken configuration. It is also not
 * a trade of certainty for a chance: every way this probe fails is a way the real connect fails too —
 * a refused socket, a sandbox, no route — with the single exception of a lost ephemeral draw, which is
 * kept disjoint from the peer's paths. What the fallback would buy is therefore not a path but a
 * *mislabelled* failure: an `EADDRINUSE` from `connect0` one syscall later, reported as
 * `LocalPathUnavailable` with a cause that names neither the probe nor the reason.
 *
 * Failing costs the caller nothing it had: migration is optional and retryable, the connection keeps
 * living on the path it is on, and `AutoMigrationWiring` already classifies `LocalPathUnavailable` as
 * retryable-without-new-information, so the reactor asks again. What it gains is that the answer names
 * the actual failure the first time it happens.
 *
 * That last paragraph is the *migration* argument and it does not transfer to a connect, which has no
 * position to keep living in: refusing it costs the caller the connection. That case reaches the same
 * conclusion on the stronger ground that the unnamed bind does not buy a connection either — see
 * `UdpSocketChannelFactory.openPrimaryChannel`.
 */
@InternalQuicApi
class UnresolvedRouteSourceException(
    val reason: RouteProbeFailure,
) : RuntimeException(reason.describe(), (reason as? RouteProbeFailure.ProbeRefused)?.cause)
