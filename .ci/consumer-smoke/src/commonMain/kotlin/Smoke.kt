package consumer.smoke

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.InterfaceIndex
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.NetworkInterfaceInfo
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.default
import com.ditchoom.socket.enumerateNetworkInterfaces
import com.ditchoom.socket.networkId
import com.ditchoom.socket.http3.Http3Request
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.transport.NetworkId
import kotlin.time.Duration.Companion.seconds

/**
 * Touches the published API surface a real consumer uses, on EVERY declared target. Compiling this
 * in commonMain is already stronger than dependency resolution — it catches an API that resolves but
 * does not compile. [connectAndGet] additionally references [withHttp3Connection], which on Kotlin/Native
 * pulls the whole QUIC backend (socket-http3 → socket-quic → socket-quic-quiche cinterop → libquiche.a):
 * linking a K/N test binary that reaches it is what turns a missing/undistributed static lib into the
 * link-time undefined-symbol failure it really is, rather than letting it slip past resolution.
 */
object Smoke {
    fun apiSurface(): QuicOptions = QuicOptions(alpnProtocols = listOf("h3"), verifyPeer = false, idleTimeout = 10.seconds)

    /** Never asserted on here — its purpose is to make the full QUIC/H3 stack reachable for the K/N linker. */
    suspend fun connectAndGet(host: String, port: Int): Int =
        withHttp3Connection(
            host,
            port,
            apiSurface(),
            TransportConfig(bufferFactory = BufferFactory.deterministic()),
            timeout = 5.seconds,
        ) {
            val r = request(Http3Request(method = "GET", authority = host, path = "/"))
            try {
                r.status
            } finally {
                r.close()
            }
        }

    /**
     * Network awareness, reached through `com.ditchoom:socket` **alone** — note that no
     * `com.ditchoom:network-monitor` dependency is declared in this project's build file, deliberately.
     *
     * Since issue #269 every [NetworkMonitor], [NetworkMonitor.Companion.default] and
     * [enumerateNetworkInterfaces] lives in that separate published module, re-exported over socket's
     * `api` edge. Two things only an external consumer can prove, and neither is exercised by the
     * source-built lanes (where it is a *project* dependency):
     *
     * 1. socket's published POM/module metadata carries `network-monitor` at **compile** scope, so this
     *    file compiles on every declared target without naming it.
     * 2. On Kotlin/Native the Apple `NWPathMonitor` + `getifaddrs` helpers and the Linux netlink
     *    helpers must actually be embedded in the published cinterop klibs. Reaching [default] and
     *    [enumerateNetworkInterfaces] from a linked test binary is the same Gap-A shape the `quiche_*`
     *    symbols get: `ld64` has no `--unresolved-symbols=ignore`, so a helper left out of the
     *    published klib fails the Apple link rather than surviving to runtime.
     *
     * Returns a description rather than asserting: the values are host-dependent (a CI runner's
     * interface set and rung are whatever they are), and the point is reaching the calls at all.
     */
    fun networkAwareness(): String {
        val interfaces: List<NetworkInterfaceInfo> = enumerateNetworkInterfaces()
        val firstIndex: InterfaceIndex? = interfaces.firstOrNull()?.index

        // processDefault() is the entry point real consumers use (QUIC auto-migration reads it). It is
        // a process-wide singleton by contract, so it is deliberately NOT closed here.
        val shared: NetworkMonitor = NetworkMonitor.processDefault()
        val sharedCapability: MonitorCapability = shared.capability

        // default() constructs a fresh, caller-owned monitor — this one we do close.
        val monitor: NetworkMonitor = NetworkMonitor.default()
        return try {
            val state: NetworkState = monitor.state.value
            val id: NetworkId = state.networkId
            "interfaces=${interfaces.size} firstIndex=${firstIndex?.value} " +
                "state=$state id=$id capability=${monitor.capability} shared=$sharedCapability"
        } finally {
            monitor.close()
        }
    }
}
