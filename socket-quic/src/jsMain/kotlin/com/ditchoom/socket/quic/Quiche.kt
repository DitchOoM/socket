package com.ditchoom.socket.quic

/**
 * koffi-backed access to libquiche on Node.js.
 *
 * [quicheLibrary] is a lazy handle: it calls `koffi.load(...)` only on first
 * access, so importing this file on browser builds is free (webpack never
 * sees a static reference to koffi — everything flows through [koffi]'s
 * dynamic `require`). Browser callers MUST NOT touch [quicheLibrary]; the
 * [isNode] guard in [JsQuicEngine] keeps them out.
 *
 * Path resolution: checks `LIBQUICHE_PATH` env var first, otherwise falls
 * back to `socket-quic/libs/quiche/<os>-<arch>/lib/libquiche.{dylib,so}`
 * relative to `process.cwd()`. The env-var path is what downstream consumers
 * will use in production — the CWD-relative path is only reliable from the
 * socket-quic project root (e.g., local dev + gradle test invocations).
 */
internal val quicheLibrary: dynamic by lazy { koffi.load(resolveLibquichePath()) }

private fun resolveLibquichePath(): String {
    val envPath = js("process.env.LIBQUICHE_PATH").unsafeCast<String?>()
    if (envPath != null && envPath.isNotEmpty()) return envPath
    val platform = js("process.platform").unsafeCast<String>()
    val arch = js("process.arch").unsafeCast<String>()
    val os =
        when (platform) {
            "darwin" -> "macos"
            "linux" -> "linux"
            else -> throw UnsupportedOperationException("Unsupported platform for libquiche: $platform")
        }
    val archDir =
        when (arch) {
            "arm64" -> "arm64"
            "x64" -> "x64"
            else -> throw UnsupportedOperationException("Unsupported arch for libquiche: $arch")
        }
    val ext = if (platform == "darwin") "dylib" else "so"
    return "libs/quiche/$os-$archDir/lib/libquiche.$ext"
}

/** `const char *quiche_version(void)` — returns the linked quiche version (e.g. "0.28.0"). */
internal fun quicheVersion(): String =
    quicheLibrary
        .func("const char* quiche_version()")
        .unsafeCast<() -> String>()
        .invoke()
