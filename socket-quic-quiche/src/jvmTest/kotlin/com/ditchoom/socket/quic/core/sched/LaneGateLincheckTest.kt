package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [LaneGate] as a socket uses it: callers borrow a lane, send from it and give it back; the loop shuts the socket,
 * cuts lanes that stalled, or stops. A socket call is a check that the socket is still open. The audit: no lane is
 * lent after a shut, no call reaches a closed socket, the gate is quiet only when nothing that could send is out,
 * at most one thread is signalled to finish the close, and a socket that was shut ends closed exactly once.
 */
abstract class LaneGateModel(
    private val bracketsSends: Boolean,
) {
    private val gate = LaneGate()
    private val shutDone = AtomicInteger()
    private val lanesHeld = AtomicInteger()
    private val inside = AtomicInteger()
    private val closes = AtomicInteger()
    private val signals = AtomicInteger()
    private val pokes = AtomicInteger()
    private val loopAsked = AtomicInteger()
    private val loopGone = AtomicInteger()
    private val violations = AtomicInteger()
    private val cutDone = AtomicInteger()

    @Operation
    fun callerOne() = borrowSendReturn()

    @Operation
    fun callerTwo() = borrowSendReturn()

    @Operation(nonParallelGroup = LOOP)
    fun loopShuts() = loopAsks { gate.shut(Closer.Loop) }

    @Operation(nonParallelGroup = LOOP)
    fun loopCutsStalledLanes() {
        cutDone.set(1)
        loopAsks { gate.cut(Closer.Loop) }
    }

    @Operation(nonParallelGroup = LOOP)
    fun loopStops() {
        if (loopGone.get() != 0) return
        loopAsked.incrementAndGet()
        val shut = gate.shut(Closer.LastLane)
        markShut()
        quietMeansNothingOut(shut)
        if (shut === Shutdown.Quiet) close()
        loopGone.set(1)
    }

    /** After every thread has finished: the loop runs once more if a thread woke it. */
    @Validate
    fun validate() {
        if (loopGone.get() == 0 && pokes.get() > 0) {
            if (gate.shut(Closer.Loop) === Shutdown.Quiet) close() else violations.incrementAndGet()
        }
        val closedOnce = if (loopAsked.get() > 0) closes.get() == 1 else closes.get() == 0
        check(violations.get() == 0 && signals.get() <= 1 && closedOnce && lanesHeld.get() == 0 && gate.lanesOut() == 0)
    }

    private fun borrowSendReturn() {
        val shutBefore = shutDone.get()
        when (gate.reserve()) {
            Lend.Shut -> return
            Lend.Lent -> if (shutBefore != 0) violations.incrementAndGet()
        }
        lanesHeld.incrementAndGet()
        when (gate.enterSend()) {
            SendEntry.Cut -> Unit
            SendEntry.Entered ->
                if (bracketsSends) {
                    inside.incrementAndGet()
                    callSocket()
                    inside.decrementAndGet()
                    lastOut(gate.leaveSend())
                } else {
                    // The control: checks the gate, then calls the socket outside it.
                    lastOut(gate.leaveSend())
                    callSocket()
                }
        }
        lanesHeld.decrementAndGet()
        lastOut(gate.back())
    }

    private fun callSocket() {
        if (closes.get() != 0) violations.incrementAndGet()
    }

    private fun lastOut(back: LaneBack) {
        when (back) {
            LaneBack.Returned -> Unit
            LaneBack.LoopCloses -> {
                signals.incrementAndGet()
                pokes.incrementAndGet()
            }
            LaneBack.ThisThreadCloses -> {
                signals.incrementAndGet()
                close()
            }
        }
    }

    private inline fun loopAsks(ask: () -> Shutdown) {
        if (loopGone.get() != 0) return
        loopAsked.incrementAndGet()
        val shut = ask()
        markShut()
        quietMeansNothingOut(shut)
        if (shut === Shutdown.Quiet) {
            close()
            loopGone.set(1)
        }
    }

    /** Marked once the shut returned: a lane whose borrow began after this saw a shut gate. */
    private fun markShut() = shutDone.set(1)

    private fun quietMeansNothingOut(shut: Shutdown) {
        if (shut === Shutdown.Quiet && inside.get() != 0) violations.incrementAndGet()
        // Before a cut, quiet also means every lane is back.
        if (shut === Shutdown.Quiet && cutDone.get() == 0 && lanesHeld.get() != 0) violations.incrementAndGet()
    }

    private fun close() {
        if (closes.incrementAndGet() != 1) violations.incrementAndGet()
    }

    protected fun options(): ModelCheckingOptions =
        ModelCheckingOptions()
            .sequentialSpecification(LaneGateSpec::class.java)
            .threads(3)
            .actorsPerThread(2)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))

    private companion object {
        const val LOOP = "loop"
    }
}

class LaneGateLincheckTest : LaneGateModel(bracketsSends = true) {
    @Test
    fun noLaneAfterAShutNoSendOnAClosedSocketAndTheLastOneOutSignals() {
        options().check(this::class)
    }
}

/** The control: a lane that checks the gate and then calls the socket outside it. The model must catch it. */
class LaneGateUnbracketedSendLincheckTest : LaneGateModel(bracketsSends = false) {
    @Test
    fun theModelCatchesASendOutsideTheGate() {
        assertFailsWith<LincheckAssertionError> { options().check(this::class) }
    }
}

class LaneGateSpec {
    fun callerOne() = Unit

    fun callerTwo() = Unit

    fun loopShuts() = Unit

    fun loopCutsStalledLanes() = Unit

    fun loopStops() = Unit
}
