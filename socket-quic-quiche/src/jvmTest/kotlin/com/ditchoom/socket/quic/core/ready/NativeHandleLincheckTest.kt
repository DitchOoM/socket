package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * [NativeHandle] as the poller uses it: other threads call through it (a wake) while two owners close it. The audit:
 * the handle is released exactly once if it was closed and never otherwise, and no call runs on a released handle.
 */
class NativeHandleLincheckTest {
    private val releases = AtomicInteger()
    private val violations = AtomicInteger()
    private val closes = AtomicInteger()
    private val handle = NativeHandle(HANDLE) { released(it) }

    @Operation
    fun callOne() = call()

    @Operation
    fun callTwo() = call()

    @Operation
    fun closeOne() = close()

    @Operation
    fun closeTwo() = close()

    @Validate
    fun validate() {
        val expected = if (closes.get() > 0) 1 else 0
        check(violations.get() == 0 && releases.get() == expected)
    }

    private fun call() {
        handle.use({ if (it != HANDLE || releases.get() != 0) violations.incrementAndGet() }, { })
    }

    private fun close() {
        closes.incrementAndGet()
        handle.close()
    }

    private fun released(raw: Long) {
        if (raw != HANDLE) violations.incrementAndGet()
        releases.incrementAndGet()
    }

    @Test
    fun aHandleIsReleasedOnceAndNoCallRunsOnItAfterward() {
        ModelCheckingOptions()
            .sequentialSpecification(NativeHandleSpec::class.java)
            .threads(3)
            .actorsPerThread(2)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }

    private companion object {
        const val HANDLE = 0x5EED_0000L
    }
}

class NativeHandleSpec {
    fun callOne() = Unit

    fun callTwo() = Unit

    fun closeOne() = Unit

    fun closeTwo() = Unit
}
