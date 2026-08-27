package com.ditchoom.socket.transport

import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Proves Lincheck's instrumentation is live in this module, by model checking a counter that is
 * definitely broken and asserting it gets caught.
 *
 * ## Why a test whose subject is the test framework
 *
 * Lincheck fails *open*. When its bytecode instrumentation cannot hook a class it logs
 * `Unable to transform <class>, proceeding without instrumentation` to stderr, explores no
 * interleavings, and reports the run as passing. Nothing in the Gradle output distinguishes "no
 * interleaving violates the invariant" from "no interleaving was ever tried".
 *
 * That is not hypothetical. In the sibling `buffer` repo, Kover's coverage agent had already
 * rewritten the bytecode Lincheck needed to analyse: all 22 classes failed to transform, and the
 * suite went green in 1.5s having model checked nothing at all. `:socket:lincheckTest` exists to run
 * on a JVM with no other agents attached; this probe is what makes that observable rather than
 * assumed.
 *
 * If this test ever fails, Lincheck has stopped detecting a guaranteed bug, and **every other
 * Lincheck result in this module is void until it passes again** — whatever those results say.
 */
class LincheckHarnessProbe {
    /** Plain `Int`, incremented non-atomically: two threads must be able to lose an update. */
    private var counter = 0

    @Operation
    fun increment(): Int = ++counter

    @Operation
    fun get(): Int = counter

    @Test
    fun lincheckDetectsAGuaranteedRace() {
        assertFailsWith<LincheckAssertionError>(
            "Lincheck passed a non-atomic counter under two concurrent incrementers. Its " +
                "instrumentation is inert — check stderr for 'Unable to transform', and confirm no " +
                "coverage or bytecode agent is attached to this task's JVM.",
        ) {
            ModelCheckingOptions()
                .threads(2)
                .actorsPerThread(2)
                .check(this::class)
        }
    }
}
