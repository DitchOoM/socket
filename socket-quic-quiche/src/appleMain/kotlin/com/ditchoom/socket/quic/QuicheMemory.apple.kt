package com.ditchoom.socket.quic

/**
 * Nothing to fence: a Kotlin/Native buffer's native memory is released by an explicit
 * `freeNativeMemory()`, never by the runtime noticing the owner went out of scope, so no collector
 * can pull the ground out from under an address the driver already handed to quiche.
 *
 * The empty body is the *answer*, not a stub — see [QuicheMemory] for the JVM/Android case it
 * exists to distinguish this platform from.
 */
internal actual fun reachabilityFence(owner: Any) = Unit
