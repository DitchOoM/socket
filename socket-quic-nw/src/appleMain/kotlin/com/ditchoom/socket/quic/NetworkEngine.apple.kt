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
        if (useSwiftBackend()) {
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
        if (useSwiftBackend()) {
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
     * Apple QUIC runs on the OS-26 Swift `NetworkConnection<QUIC>` backend ([connectQuicSwift] /
     * [buildAppleQuicSwiftServer]) for every connection on macOS/iOS 26+. Unlike the legacy
     * `nw_connection_group` path it carries datagrams AND inbound streams on one connection (issue #173 —
     * the group backend extracts a datagram flow that suppresses inbound-stream delivery), and it has full
     * parity with the group path on the raw-QUIC surface (cert pinning, the server anti-amplification
     * guard, keepalive, bulk transfer, and impairment recovery), so there is no longer a case for the
     * group backend at runtime.
     *
     * The legacy path stays reachable ONLY via [backendOverrideForTest] (so a single OS-26 runner can still
     * exercise it) until it is removed outright. The Apple deployment floor is macOS/iOS 26+, so below 26
     * there is no Apple QUIC backend at all (no quiche-on-Apple fallback); [isAppleOS26OrLater] gates that.
     */
    private fun useSwiftBackend(): Boolean =
        when (backendOverrideForTest) {
            AppleQuicBackend.Swift -> true
            AppleQuicBackend.Legacy -> false
            null -> isAppleOS26OrLater()
        }
}

/**
 * The two Apple QUIC backends [NetworkEngine] chooses between. Used only by
 * [NetworkEngine.backendOverrideForTest] so a single OS-26 runner can exercise both the OS-26 Swift
 * `NetworkConnection<QUIC>` path and the legacy `nw_connection_group` path (see that field's KDoc).
 */
internal enum class AppleQuicBackend { Swift, Legacy }
