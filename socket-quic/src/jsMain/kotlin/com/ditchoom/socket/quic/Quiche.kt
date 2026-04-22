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

/**
 * koffi type descriptors for the two quiche info structs we allocate in [KoffiQuicheApi].
 *
 * Layouts were verified against libquiche 0.28.0 on macOS arm64 with a direct C compile
 * (sizeof + offsetof) — they match exactly:
 *
 * - `quiche_recv_info` (32 bytes): void* from, uint32_t from_len, void* to, uint32_t to_len.
 * - `quiche_send_info` (288 bytes): sockaddr_storage from (128, 8-aligned), uint32_t from_len,
 *   sockaddr_storage to (128, 8-aligned) at offset 136, uint32_t to_len at offset 264,
 *   struct timespec at at offset 272.
 *
 * We model sockaddr_storage as `uint64_t[16]` (128 bytes, 8-byte alignment) rather than
 * `uint8_t[128]` so the koffi-computed field offsets match what C emits.
 */
internal val sockaddrStorageType: dynamic by lazy { koffi.array("uint64_t", 16) }

internal val recvInfoType: dynamic by lazy {
    koffi.struct(
        "quiche_recv_info",
        js(
            "({ from: 'void*', from_len: 'uint32_t', to: 'void*', to_len: 'uint32_t' })",
        ),
    )
}

internal val timespecType: dynamic by lazy {
    koffi.struct(
        "timespec",
        js("({ tv_sec: 'int64_t', tv_nsec: 'int64_t' })"),
    )
}

internal val sendInfoType: dynamic by lazy {
    val fields =
        js(
            "({ from: null, from_len: 'uint32_t', to: null, to_len: 'uint32_t', at: null })",
        )
    fields.from = sockaddrStorageType
    fields.to = sockaddrStorageType
    fields.at = timespecType
    koffi.struct("quiche_send_info", fields)
}

/** Byte offset of `quiche_send_info.to` — verified to equal 136 on 64-bit macOS/Linux. */
internal const val SEND_INFO_OFFSET_TO: Long = 136
