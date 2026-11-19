package com.ditchoom.socket.quic.core.ready

import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.function.Consumer

/** The selected-key set, for a runtime without the consumer form; allocates a node per ready key. */
internal class SetScan(
    private val selector: Selector,
    private val onReady: Consumer<SelectionKey>,
) {
    fun forever(): Int = drain(selector.select())

    fun timed(millis: Long): Int = drain(selector.select(millis))

    fun now(): Int = drain(selector.selectNow())

    private fun drain(ready: Int): Int {
        val keys = selector.selectedKeys().iterator()
        while (keys.hasNext()) {
            val key = keys.next()
            keys.remove()
            onReady.accept(key)
        }
        return ready
    }
}
