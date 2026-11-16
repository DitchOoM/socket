package com.ditchoom.socket.quic.core.ready

import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.function.Consumer

/** The JDK's `Selector.select(Consumer)` family: allocation-free, absent from Android before API 33. */
internal class ConsumerScan(
    private val selector: Selector,
    private val onReady: Consumer<SelectionKey>,
) {
    fun forever(): Int = selector.select(onReady)

    fun timed(millis: Long): Int = selector.select(onReady, millis)

    fun now(): Int = selector.selectNow(onReady)
}
