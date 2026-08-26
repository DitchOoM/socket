package com.ditchoom.socket.transport

import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The teardown-once contract, model checked against both shapes this codebase uses for it.
 *
 * ## The contract
 *
 * `close()` may be called by any number of callers concurrently, and the resource it owns must be
 * released **exactly once**. #471 established the reasoning and the mechanism for [CodecConnection]
 * and [CodecSender]; [ReconnectingConnection] was edited 21 minutes later and still guards teardown
 * with a bare `@Volatile var closed`.
 *
 * ## Why two arms
 *
 * A conformance test that only ever runs against the implementation believed to be correct proves
 * nothing about its own sensitivity — the lesson [LincheckHarnessProbe] exists to enforce, learned
 * the expensive way. So the known-bad shape is checked too, and is required to fail. If
 * [bareBooleanGuardAdmitsADoubleRelease] ever passes, this suite has stopped being able to see the
 * defect it was written to catch, and [latchedTeardownReleasesExactlyOnce] passing means nothing.
 *
 * Both subjects are reduced to the guard alone — no sockets, no reconnect loop — because that is
 * what is under test. [ReconnectingConnection.close] additionally has to reach a connected state
 * before its double-release is observable, which needs the whole machine and is why the end-to-end
 * proof for it belongs with the thread probers in [ReconnectingConnectionCollectorRaceTests] rather
 * than here.
 */
private class CountingResource {
    val closes = AtomicInteger(0)

    suspend fun close() {
        closes.incrementAndGet()
    }
}

/**
 * `ReconnectingConnection.close()`'s guard as it stands today, lifted verbatim:
 *
 * ```
 * if (closed) return
 * closed = true
 * currentConnection?.close()
 * ```
 *
 * `@Volatile` publishes the write; it does not make the read-then-write pair atomic. This arm MUST
 * fail — see the class doc on [TeardownOnceLincheckTest].
 */
class BareBooleanTeardownLincheckTest {
    private val resource = CountingResource()

    @Volatile
    private var closed = false

    @Operation
    suspend fun close() {
        if (closed) return
        closed = true
        resource.close()
    }

    @Validate
    fun releasedAtMostOnce() {
        val closes = resource.closes.get()
        check(closes <= 1) { "the resource was released $closes times; teardown must release it once" }
    }

    @Test
    fun bareBooleanGuardAdmitsADoubleRelease() {
        assertFailsWith<LincheckAssertionError>(
            "the check-then-act boolean guard survived model checking. Either Lincheck's " +
                "instrumentation is inert (see LincheckHarnessProbe) or this arm no longer models " +
                "the shape it was written to convict.",
        ) {
            ModelCheckingOptions()
                .threads(2)
                .actorsPerThread(1)
                .check(this::class)
        }
    }
}

/**
 * [TeardownOnce], the extracted primitive — the shape #471 established, now a type rather than
 * seven lines each of [CodecConnection], [CodecSender] and [ReconnectingConnection] rewrote by hand.
 * This is its conformance test, and the arm above is the control proving the test can tell the two
 * apart.
 */
class LatchedTeardownLincheckTest {
    private val resource = CountingResource()
    private val teardown = TeardownOnce()

    @Operation
    suspend fun close() = teardown.runOnce { resource.close() }

    @Validate
    fun releasedAtMostOnce() {
        val closes = resource.closes.get()
        check(closes <= 1) { "the resource was released $closes times; teardown must release it once" }
    }

    @Test
    fun latchedTeardownReleasesExactlyOnce() {
        ModelCheckingOptions()
            .threads(3)
            .actorsPerThread(1)
            .check(this::class)
    }
}
