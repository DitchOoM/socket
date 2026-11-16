package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.core.time.WaitResolution
import java.nio.channels.Selector
import java.util.function.Consumer

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
    val waits = if (System.getProperty("os.name").lowercase().contains("win")) WINDOWS_TIMER_TICK else MILLISECOND
    return NetStack.Jdk(if (consumerForm) SelectedKeys.ByConsumer else SelectedKeys.BySet, waits)
}

/** Windows wakes a timed wait on its 64 Hz timer tick (15.625 ms) unless something raised the timer resolution. */
private val WINDOWS_TIMER_TICK = WaitResolution(16_000_000L)
private val MILLISECOND = WaitResolution(1_000_000L)
