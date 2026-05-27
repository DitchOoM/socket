package com.ditchoom.socket.quic

import kotlinx.coroutines.Job

/**
 * No-op on Android.
 *
 * `java.lang.ref.Cleaner` is only present on API 33+, but `socket-quic`'s
 * `minSdk` is 24. Rather than gate the registration at runtime via
 * `Build.VERSION.SDK_INT` (and pull in `android.os.Build` everywhere the
 * engines live), the safety net is simply omitted on Android: the
 * scope-only `withQuicEngine` / `withQuicServerEngine` helpers remain the
 * primary defence against engine leaks on every platform, and Android
 * instrumented tests are typically too few to trigger the resource-pressure
 * threshold that motivated the Cleaner on the GH ubuntu-24.04 runner.
 *
 * Revisit once `minSdk` reaches 33 (Android 13) or if Android instrumented
 * tests start exhibiting the same late-suite handshake hang.
 */
internal actual fun registerEngineCleanup(
    @Suppress("UNUSED_PARAMETER") engine: Any,
    @Suppress("UNUSED_PARAMETER") job: Job,
) {
    // No-op
}
