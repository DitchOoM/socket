package com.ditchoom.socket

/**
 * Android actual for [NetworkMonitor.Companion.default].
 *
 * The reactive [AndroidNetworkMonitor] needs an [android.content.Context] for `ConnectivityManager`,
 * which a zero-arg factory cannot conjure. `:network-monitor` supplies one anyway:
 * [NetworkMonitorInitializer] captures the application context via androidx.startup before any app code
 * runs, so [androidOrNull] can build a `ConnectivityManager`-backed monitor here
 * ([MonitorMechanism.PlatformSignalled]) with nothing asked of the app.
 *
 * The fallback is reached only when no context was ever recorded — the app stripped the initializer
 * from its merged manifest and called neither [installAndroidApplicationContext] nor
 * [installAndroidContext]. It is [PollingNetworkMonitor], matching the JVM actual, **not**
 * [NetworkMonitor.AlwaysAvailable] as it was before: `java.net.NetworkInterface` needs no permission on
 * Android and does observe an interface going down, so polling is late but true, whereas
 * `AlwaysAvailable` reports the network as permanently up and is simply false the moment it isn't. The
 * degradation is not silent — the returned monitor reports [MonitorMechanism.Polled], which a consumer
 * gating on reactivity (`../webrtc`'s `IceRestartPolicy.OnNetworkChange`) can branch on at configuration
 * time.
 *
 * This does not throw. [processDefault] is read by QUIC auto-migration on every connection open, so a
 * missing `Context` must degrade a best-effort optimization, not fail the connection. The genuinely
 * dead case — `ACCESS_NETWORK_STATE` stripped, so the callback would never fire — does throw, as
 * [NetworkMonitorPermissionException] from [AndroidNetworkMonitor]'s constructor.
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = androidOrNull() ?: PollingNetworkMonitor()
