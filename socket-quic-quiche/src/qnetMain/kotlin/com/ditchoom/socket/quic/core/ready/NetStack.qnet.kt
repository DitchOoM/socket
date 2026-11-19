package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.CinteropNetShim
import com.ditchoom.socket.quic.core.time.WaitResolution

/**
 * Kotlin/Native: `qnet` through its cinterop, on every target. Timed waits are held to a millisecond, as on the JVM,
 * so pacing releases alike on every platform.
 */
internal actual val netStackOfThisRuntime: NetStack = NetStack.Native(CinteropNetShim, WaitResolution(1_000_000L))
