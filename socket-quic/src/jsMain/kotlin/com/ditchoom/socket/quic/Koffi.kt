package com.ditchoom.socket.quic

/**
 * Lazy dynamic require of the koffi npm package.
 *
 * Loading via `js("require('koffi')")` keeps the reference invisible to browser
 * webpack bundlers — the string literal isn't parsed as an import and never has
 * to resolve at browser build time. The Node-only FFI code calls [koffi] only
 * inside an [isNode] guard, so browsers never evaluate it.
 */
internal val koffi: dynamic by lazy { js("require('koffi')") }

internal val isNode: Boolean by lazy {
    js(
        "typeof process !== 'undefined' && process.versions != null && process.versions.node != null",
    ) as Boolean
}

/** Returns koffi's reported version string. Exposed for smoke tests that verify the FFI loader is live. */
internal fun koffiVersion(): String = koffi.version.unsafeCast<String>()
