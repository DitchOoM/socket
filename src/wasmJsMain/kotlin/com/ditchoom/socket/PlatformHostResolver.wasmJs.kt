package com.ditchoom.socket

/** WASM has no sockets and no resolver; the answer says so instead of throwing. */
internal actual fun platformHostResolver(): HostResolver =
    HostResolver { host -> Resolution.Failed(host, UnsupportedOperationException("Sockets are not supported in WASM")) }
