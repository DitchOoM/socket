package com.ditchoom.socket.testkit.skip

internal actual fun testkitEnv(name: String): String? {
    // Node exposes env via process.env; guard for browser hosts where `process` is undefined.
    val env = js("(typeof process !== 'undefined' && process.env) ? process.env : null")
    if (env == null) return null
    val value = env[name]
    return (value as? String)?.takeIf { it.isNotEmpty() }
}
