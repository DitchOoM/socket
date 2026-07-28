package com.ditchoom.socket

import android.content.Context
import androidx.startup.Initializer

/**
 * androidx.startup [Initializer] that hands the application [Context] to this module at process start,
 * so `NetworkMonitor.default()` / `.processDefault()` are reactive on Android with **no** app-side call.
 *
 * Before this existed, a working Android monitor required the app to remember
 * `NetworkMonitor.installAndroidContext(applicationContext)` in `Application.onCreate()`; forgetting it
 * silently degraded every `processDefault()` consumer (QUIC auto-migration, transport fallback's
 * per-network capability cache) to [NetworkMonitor.AlwaysAvailable] — a no-op that reports the network
 * as permanently up. Nothing failed loudly, which is exactly the failure mode App Startup removes: this
 * module's manifest contributes a `<meta-data>` entry to `androidx.startup.InitializationProvider`, so
 * the merged app manifest runs [create] from that provider's `onCreate` — after
 * `Application.attachBaseContext` and before `Application.onCreate`, ahead of any app code.
 *
 * **This only captures the context; it does not build a monitor.** Constructing an
 * [AndroidNetworkMonitor] registers a `ConnectivityManager.NetworkCallback`, and an app that merely
 * links this library should not pay that (or the wakeups it implies) at startup. The callback is
 * registered lazily, on the first `NetworkMonitor.processDefault()` read.
 *
 * To opt out entirely, remove the entry in the app's manifest:
 *
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="merge">
 *     <meta-data android:name="com.ditchoom.socket.NetworkMonitorInitializer" tools:node="remove" />
 * </provider>
 * ```
 *
 * With it removed, `NetworkMonitor.default()` falls back to [NetworkMonitor.AlwaysAvailable] unless the
 * app supplies a context itself via [NetworkMonitor.Companion.installAndroidApplicationContext] or
 * installs a monitor via [NetworkMonitor.Companion.installAndroidContext].
 */
class NetworkMonitorInitializer : Initializer<Context> {
    override fun create(context: Context): Context {
        NetworkMonitor.installAndroidApplicationContext(context)
        return context.applicationContext
    }

    /** No other initializer has to run first — this only stores a reference. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
