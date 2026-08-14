@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.ditchoom.socket.testkit.skip

// Node exposes env via process.env; guard for browser hosts where `process` is undefined. `js(...)`
// must be the whole body of the function on wasmJs, so the lookup is its own declaration.
private fun readEnv(name: String): String? =
    js(
        "((typeof process !== 'undefined' && process.env && process.env[name] != null) ? String(process.env[name]) : null)",
    )

internal actual fun testkitEnv(name: String): String? = readEnv(name)?.takeIf { it.isNotEmpty() }
