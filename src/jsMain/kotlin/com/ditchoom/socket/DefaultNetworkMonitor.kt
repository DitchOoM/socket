package com.ditchoom.socket

/**
 * JS actual for [NetworkMonitor.Companion.default].
 *
 * [JsNetworkMonitor] is reactive in the browser (`online`/`offline` events) and
 * polls `os.networkInterfaces()` under Node.js, and is zero-arg constructible.
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = JsNetworkMonitor()
