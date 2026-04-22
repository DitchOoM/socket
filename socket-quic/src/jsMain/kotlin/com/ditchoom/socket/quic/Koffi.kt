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

/**
 * Extract the numeric address of a koffi pointer (External) as a Kotlin [Long].
 *
 * `koffi.address(ptr)` returns a JS BigInt — we stringify it so the value survives the
 * Kotlin Long parse without depending on engine-specific BigInt ↔ Long bridging.
 * Verified [2026-04-22]: koffi's External does NOT own the underlying native memory,
 * so discarding the External after extracting the address is safe; the pointer stays
 * valid until the owning C API (e.g. `quiche_config_free`) is called with the same address.
 */
internal fun addressOf(pointer: dynamic): Long =
    koffi
        .address(pointer)
        .toString()
        .unsafeCast<String>()
        .toLong()

/**
 * Convert a Kotlin [Long] pointer address back into a value koffi will accept as `void*`.
 * koffi's C-ABI layer transparently handles numeric BigInt for void*, int64, uint64 args.
 */
internal fun Long.asPointer(): dynamic = js("BigInt")(this.toString())
