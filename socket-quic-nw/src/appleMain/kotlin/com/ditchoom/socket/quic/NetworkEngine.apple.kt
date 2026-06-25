package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Apple [QuicEngine] backed by Network.framework (system QUIC; zero app-size cost). No quiche. The
 * `withQuicConnection` / `withQuicServer` wrappers (in `:socket-quic-default`) own the lifecycle;
 * this engine just builds + establishes.
 *
 * Public SPI: `:socket-quic-default` names this as the Apple `defaultQuicEngine` actual.
 *
 * Network.framework does not expose controllable connection migration, so [EngineCapabilities.
 * supportsMigration] is false (a connection's `migrate()` returns [MigrationResult.Unsupported]).
 */
object NetworkEngine : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            supportsMigration = false,
            supportsDatagrams = true,
            supportsServer = true,
        )

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection =
        if (useSwiftBackend(quicOptions)) {
            connectQuicSwift(hostname, port, quicOptions, transport, timeout)
        } else {
            connectQuicGroup(hostname, port, quicOptions, transport, timeout)
        }

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer =
        if (useSwiftBackend(quicOptions)) {
            buildAppleQuicSwiftServer(port, host, tlsConfig, quicOptions, timeout)
        } else {
            buildAppleQuicServer(port, host, tlsConfig, quicOptions, timeout)
        }

    /**
     * Test-only override of the backend-selection rule in [useSwiftBackend]. `null` (the default and
     * the only production value) defers to the real OS-driven rule. The two backends are otherwise
     * selected purely by the host OS version, so on any single runner only one of them runs and the
     * other rots untested — in particular, on a macOS/iOS-26 runner the legacy `nw_connection_group`
     * datagram+`PreferStreams` fallback (what every pre-26 client actually executes) is never
     * exercised. `AppleBackendSelectionTest` sets this to force each path on one OS-26 machine and
     * MUST reset it to `null` in a `finally`. Internal: visible only to this module's `appleTest`.
     */
    internal var backendOverrideForTest: AppleQuicBackend? = null

    /**
     * Pick the OS-26 Swift `NetworkConnection<QUIC>` backend ([connectQuicSwift] /
     * [buildAppleQuicSwiftServer]) over the legacy `nw_connection_group` path only when datagrams AND
     * inbound streams must coexist on one connection — the case the group backend cannot serve (issue
     * #173): it extracts a datagram flow that suppresses inbound-stream delivery. That requirement is
     * signalled by [DatagramStreamConflictPolicy.PreferStreams], which the HTTP/3 / WebTransport stack
     * sets via `forHttp3()` (it needs peer-initiated control/QPACK streams). The new Swift API carries
     * both, so it wins there. Everything else stays on the proven group backend: PURE datagram use
     * ([DatagramStreamConflictPolicy.PreferDatagrams], no inbound streams) works fine on the group's
     * extracted flow, and non-datagram connections never needed the new API. Below macOS/iOS 26 we also
     * fall back to the group backend (WebTransport datagrams remain unavailable there — pre-26 needs
     * quiche-on-Apple).
     */
    private fun useSwiftBackend(quicOptions: QuicOptions): Boolean =
        when (backendOverrideForTest) {
            AppleQuicBackend.Swift -> true
            AppleQuicBackend.Legacy -> false
            null ->
                quicOptions.datagrams != null &&
                    quicOptions.datagramStreamConflictPolicy == DatagramStreamConflictPolicy.PreferStreams &&
                    isAppleOS26OrLater()
        }
}

/**
 * The two Apple QUIC backends [NetworkEngine] chooses between. Used only by
 * [NetworkEngine.backendOverrideForTest] so a single OS-26 runner can exercise both the OS-26 Swift
 * `NetworkConnection<QUIC>` path and the legacy `nw_connection_group` path (see that field's KDoc).
 */
internal enum class AppleQuicBackend { Swift, Legacy }
