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
internal val applicationContext = AtomicReference<Context?>(null)

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
