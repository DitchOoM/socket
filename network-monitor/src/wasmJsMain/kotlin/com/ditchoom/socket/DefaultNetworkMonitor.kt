package com.ditchoom.socket

/**
 * wasmJs actual for [NetworkMonitor.Companion.default].
 *
 * The base TCP socket surface is unavailable in the browser (Wasm), and there is
 * no zero-arg reactive monitor wired for this target yet, so the default is the
 * no-op [NetworkMonitor.AlwaysAvailable]. (`navigator.onLine` could back a reactive
 * monitor later; kept out of scope here to avoid Wasm/JS interop surface.)
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = NetworkMonitor.AlwaysAvailable
