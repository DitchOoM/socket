package com.ditchoom.socket.quic.core.ready

import java.nio.channels.Selector
import java.util.function.Consumer

/** The JDK stack with the key scan this runtime supports: `Selector.select(Consumer)` where it exists (Android from API 33). */
internal fun jdkStackOfThisRuntime(): NetStack.Jdk {
    val consumerForm =
        Selector::class.java.methods.any {
            it.name == "select" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Consumer::class.java
        }
    return NetStack.Jdk(if (consumerForm) SelectedKeys.ByConsumer else SelectedKeys.BySet)
}
