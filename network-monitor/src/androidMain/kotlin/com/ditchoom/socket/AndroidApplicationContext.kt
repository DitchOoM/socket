package com.ditchoom.socket

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * The process-wide application [Context] used to build the Android [NetworkMonitor].
 *
 * Normally populated automatically by [NetworkMonitorInitializer] (androidx.startup) before any
 * application code runs, so nothing has to be remembered at the call site. An app that removed the
 * initializer from its merged manifest — or one that wants to install a specific context earlier —
 * sets it via [NetworkMonitor.Companion.installAndroidApplicationContext].
 *
 * Holding the *application* context (never an Activity) is what makes a process-lifetime static safe
 * here: it is already a process singleton, so this leaks nothing that the process does not own.
 */
private val applicationContext = AtomicReference<Context?>(null)

/**
 * Records the application [Context] that [NetworkMonitor.Companion.androidOrNull] (and therefore
 * `NetworkMonitor.default()`) will use, **without** constructing a monitor or registering any
 * `ConnectivityManager` callback.
 *
 * [NetworkMonitorInitializer] calls this at process start via androidx.startup, so an app normally
 * never needs to. Call it directly only to supply the context earlier than App Startup would, or when
 * the initializer has been removed from the manifest (see [NetworkMonitorInitializer] for how).
 *
 * [Context.getApplicationContext] is applied here, so passing an Activity is safe.
 */
fun NetworkMonitor.Companion.installAndroidApplicationContext(context: Context) {
    applicationContext.set(context.applicationContext)
}

/**
 * A reactive [AndroidNetworkMonitor] if an application [Context] is known, else `null`.
 *
 * Public because the owning platform module (`com.ditchoom:socket`) delegates its Android
 * `NetworkMonitor.default()` actual here across the module boundary — the same delegation
 * [defaultJvmNetworkMonitor] uses on the JVM.
 *
 * Each call constructs a **new** monitor that registers its own `ConnectivityManager.NetworkCallback`;
 * the caller owns it and must [NetworkMonitor.close] it. `NetworkMonitor.processDefault()` already
 * calls this at most once per process, so prefer that over calling this repeatedly.
 */
fun NetworkMonitor.Companion.androidOrNull(): NetworkMonitor? = applicationContext.get()?.let(::AndroidNetworkMonitor)

/**
 * Whether an application [Context] has been captured — i.e. whether [androidOrNull] would return a
 * reactive monitor — **without constructing anything**.
 *
 * This is the configuration-time question: *"if I ask for a monitor here, will it be pushed or polled?"*
 * Every other way to answer it has a side effect. [androidOrNull] and `NetworkMonitor.default()` build
 * a monitor that registers a `ConnectivityManager.NetworkCallback`, so a caller probing for reactivity
 * has to register and immediately unregister one. `NetworkMonitor.processDefault()` is worse in the
 * negative case: it caches whatever it built for the life of the process, so probing an Android app
 * with no captured `Context` leaves a [PollingNetworkMonitor] and its 5-second coroutine running
 * forever, in a caller that then falls back to its own polling anyway.
 *
 * `../webrtc`'s ICE layer is the motivating consumer: it decides at configuration time whether
 * `IceRestartPolicy.OnNetworkChange` can be honoured, and must be able to report an honest
 * "degraded, no Android context" without paying for a monitor it will not use.
 *
 * True here does not promise the monitor will *work* — an app that stripped `ACCESS_NETWORK_STATE`
 * still throws [NetworkMonitorPermissionException] on construction. It promises only that the `Context`
 * `ConnectivityManager` needs is available.
 */
fun NetworkMonitor.Companion.hasAndroidApplicationContext(): Boolean = applicationContext.get() != null

/**
 * Clears the captured application [Context], restoring the "App Startup never ran" state.
 *
 * **Test-only**, and the Android half of [NetworkMonitor.Companion.resetProcessDefaultForTesting] —
 * see there for why a one-way process-global is untestable without it. Needed to exercise the
 * degraded path: [hasAndroidApplicationContext] returning false, [androidOrNull] returning null, and
 * the Android `NetworkMonitor.default()` falling back to [PollingNetworkMonitor].
 */
fun NetworkMonitor.Companion.resetAndroidContextForTesting() {
    applicationContext.set(null)
}
