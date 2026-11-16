package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.loadNetShim
import java.nio.channels.Selector
import java.util.function.Consumer

/** The JVM: [NetStack.Native] wherever `qnet` has a poller (macOS, Linux, Android), [NetStack.Jdk] on Windows. */
internal actual val netStackOfThisRuntime: NetStack by lazy {
    if (isWindows()) jdkStackOfThisRuntime() else NetStack.Native(loadNetShim(), MILLISECOND)
}

/**
 * The JDK stack with the key scan this runtime supports (`Selector.select(Consumer)` where it exists, Android from API
 * 33) and the step its timed waits wake on: Windows' default timer tick of 15.6 ms, a millisecond elsewhere.
 */
internal fun jdkStackOfThisRuntime(): NetStack.Jdk {
    val consumerForm =
        Selector::class.java.methods.any {
            it.name == "select" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Consumer::class.java
        }
    val waits = if (isWindows()) WINDOWS_TIMER_TICK else MILLISECOND
    return NetStack.Jdk(if (consumerForm) SelectedKeys.ByConsumer else SelectedKeys.BySet, waits, JdkRuntime())
}

/** Made only here, on a JVM. */
internal actual class JdkRuntime

internal actual fun jdkPoller(
    clock: LoopClock,
    stack: NetStack.Jdk,
): Poller = SelectorPoller(clock, stack.scan)

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

/** Windows wakes a timed wait on its 64 Hz timer tick (15.625 ms) unless something raised the timer resolution. */
private val WINDOWS_TIMER_TICK = WaitResolution(16_000_000L)

/**
 * A millisecond: the selector's unit, and what the native poller is held to as well, so pacing releases the same on
 * either stack (kqueue and epoll_pwait2 time in nanoseconds; plain epoll_wait in milliseconds).
 */
private val MILLISECOND = WaitResolution(1_000_000L)
