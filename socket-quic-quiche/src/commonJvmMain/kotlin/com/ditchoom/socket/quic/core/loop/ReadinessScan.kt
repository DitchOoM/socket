package com.ditchoom.socket.quic.core.loop

import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.function.Consumer

/**
 * How a loop reads the ready keys of its [Selector], chosen once from the runtime the loop runs on. The consumer
 * form of `select` allocates nothing per ready event; a runtime without it (Android before API 33) scans the
 * selected-key set, which allocates one node per ready event.
 */
internal sealed interface ReadinessScan {
    /** Blocks until a key is ready or the selector is woken, handing each ready key to [onReady]. */
    fun select(
        selector: Selector,
        onReady: Consumer<SelectionKey>,
    ): Int

    /** Like [select], for at most [timeoutMillis], which is positive. */
    fun select(
        selector: Selector,
        timeoutMillis: Long,
        onReady: Consumer<SelectionKey>,
    ): Int

    /** Hands each key that is ready now to [onReady] without blocking. */
    fun selectNow(
        selector: Selector,
        onReady: Consumer<SelectionKey>,
    ): Int

    /** The JDK's `Selector.select(Consumer)` family; allocation-free. */
    data object ByConsumer : ReadinessScan {
        override fun select(
            selector: Selector,
            onReady: Consumer<SelectionKey>,
        ): Int = selector.select(onReady)

        override fun select(
            selector: Selector,
            timeoutMillis: Long,
            onReady: Consumer<SelectionKey>,
        ): Int = selector.select(onReady, timeoutMillis)

        override fun selectNow(
            selector: Selector,
            onReady: Consumer<SelectionKey>,
        ): Int = selector.selectNow(onReady)
    }

    /** The selected-key set, for a runtime without the consumer form; allocates a node per ready event. */
    data object BySelectedKeys : ReadinessScan {
        override fun select(
            selector: Selector,
            onReady: Consumer<SelectionKey>,
        ): Int = drain(selector, selector.select(), onReady)

        override fun select(
            selector: Selector,
            timeoutMillis: Long,
            onReady: Consumer<SelectionKey>,
        ): Int = drain(selector, selector.select(timeoutMillis), onReady)

        override fun selectNow(
            selector: Selector,
            onReady: Consumer<SelectionKey>,
        ): Int = drain(selector, selector.selectNow(), onReady)

        private fun drain(
            selector: Selector,
            ready: Int,
            onReady: Consumer<SelectionKey>,
        ): Int {
            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()
                onReady.accept(key)
            }
            return ready
        }
    }

    companion object {
        /** The scan this runtime supports, found by asking it once whether `Selector.select(Consumer)` exists. */
        fun ofThisRuntime(): ReadinessScan =
            try {
                Selector::class.java.getMethod("select", Consumer::class.java)
                ByConsumer
            } catch (absent: NoSuchMethodException) {
                BySelectedKeys
            }
    }
}
