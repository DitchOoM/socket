@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * What a debug build checks about a driver: that quiche and the loop's own state are touched only while
 * the loop holds its *turn*.
 *
 * A turn is the stretch of the loop between two suspension points. It is owned by the thread the loop
 * resumed on, and it ends when the loop suspends: a coroutine may resume on another thread after every
 * suspension, so the owner is not a thread for the loop's life but a thread for one turn. The loop
 * [claims][Checking.resumeTurn] the turn after each suspension and [releases][Checking.suspendTurn] it
 * before the next, and a check ([assertOnLoop]) passes only while the turn is claimed and the caller is
 * the thread that claimed it. That finds an access from another thread during a turn, and an access
 * between turns, which is where a late callback, a reader or an application coroutine lands.
 *
 * Not installed in production: [Unchecked] is what a driver gets unless [DebugLoopGuard] says otherwise,
 * and it does nothing.
 */
internal sealed interface LoopGuard {
    /** The loop has started: its first turn begins here. */
    fun beginLoop()

    /** The loop is about to suspend (or is finished with its last turn): the turn ends. */
    fun suspendTurn()

    /** The loop resumed after a suspension: a new turn begins on this thread. */
    fun resumeTurn()

    /** The loop has ended; nothing may touch the connection from here. */
    fun endLoop()

    /** The caller is about to use loop-confined state at [site]. */
    fun assertOnLoop(site: LoopSite)

    /** The production guard: checks nothing, so a driver that is not under test pays one virtual call per site. */
    data object Unchecked : LoopGuard {
        override fun beginLoop() = Unit

        override fun suspendTurn() = Unit

        override fun resumeTurn() = Unit

        override fun endLoop() = Unit

        override fun assertOnLoop(site: LoopSite) = Unit
    }

    /** Records every access that is not on the loop's turn in [log]. One per driver. */
    class Checking(
        private val log: ViolationLog,
        /** Who is calling; the thread, except in a test that has to say "another thread" without a second one. */
        private val callerId: () -> Long = ::currentThreadId,
    ) : LoopGuard {
        private val phase = AtomicReference<Phase>(Phase.NotStarted)

        private sealed interface Phase {
            /** The driver is being built or has been handed to `start()`: the hand-over is ordered by the launch. */
            data object NotStarted : Phase

            /** A turn is running on [thread]. */
            class InTurn(
                val thread: Long,
            ) : Phase

            /** The loop is suspended between two turns. */
            data object Suspended : Phase

            data object Ended : Phase
        }

        override fun beginLoop() {
            log.loops.fetchAndIncrement()
            val current = phase.load()
            if (current !is Phase.NotStarted || !phase.compareAndSet(current, Phase.InTurn(callerId()))) {
                log.record(LoopViolation(LoopSite.BeginLoop, ViolationKind.LoopStartedTwice))
            }
        }

        override fun suspendTurn() {
            log.turns.fetchAndIncrement()
            val current = phase.load()
            if (current !is Phase.InTurn || current.thread != callerId() || !phase.compareAndSet(current, Phase.Suspended)) {
                log.record(LoopViolation(LoopSite.SuspendTurn, kindOf(current)))
            }
        }

        override fun resumeTurn() {
            val current = phase.load()
            if (current !is Phase.Suspended || !phase.compareAndSet(current, Phase.InTurn(callerId()))) {
                log.record(LoopViolation(LoopSite.ResumeTurn, kindOf(current)))
            }
        }

        override fun endLoop() {
            val current = phase.load()
            if (current !is Phase.InTurn || current.thread != callerId() || !phase.compareAndSet(current, Phase.Ended)) {
                log.record(LoopViolation(LoopSite.EndLoop, kindOf(current)))
            }
        }

        override fun assertOnLoop(site: LoopSite) {
            log.checks.fetchAndIncrement()
            when (val current = phase.load()) {
                Phase.NotStarted -> Unit
                is Phase.InTurn -> if (current.thread != callerId()) log.record(LoopViolation(site, kindOf(current)))
                Phase.Suspended, Phase.Ended -> log.record(LoopViolation(site, kindOf(current)))
            }
        }

        private fun kindOf(phase: Phase): ViolationKind =
            when (phase) {
                Phase.NotStarted -> ViolationKind.BeforeTheLoopStarted
                is Phase.InTurn ->
                    if (phase.thread == callerId()) ViolationKind.TurnStateOutOfOrder else ViolationKind.OtherThreadDuringTurn
                Phase.Suspended -> ViolationKind.BetweenTurns
                Phase.Ended -> ViolationKind.AfterTheLoopEnded
            }
    }
}

/** Where a guard was asked, or a loop-lifecycle edge it saw out of order. */
internal enum class LoopSite {
    BeginLoop,
    SuspendTurn,
    ResumeTurn,
    EndLoop,
    Dispatch,
    AfterCommand,
    Cleanup,
    TeardownPath,
    Quiche,
}

internal enum class ViolationKind {
    /** A turn was running, on another thread. */
    OtherThreadDuringTurn,

    /** The loop was suspended: neither it nor anything it launched may touch its state then. */
    BetweenTurns,

    AfterTheLoopEnded,
    BeforeTheLoopStarted,

    /** A turn edge out of order on the right thread: a suspension point the guard was not told about. */
    TurnStateOutOfOrder,
    LoopStartedTwice,
}

internal data class LoopViolation(
    val site: LoopSite,
    val kind: ViolationKind,
)

/** The violations and counters of every guard that reports to it; what a confinement test asserts on. */
internal class ViolationLog {
    private val recorded = AtomicReference<List<LoopViolation>>(emptyList())
    internal val loops = AtomicInt(0)
    internal val turns = AtomicInt(0)
    internal val checks = AtomicInt(0)

    val violations: List<LoopViolation> get() = recorded.load()
    val loopCount: Int get() = loops.load()
    val turnCount: Int get() = turns.load()
    val checkCount: Int get() = checks.load()

    fun record(violation: LoopViolation) {
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + violation)) return
        }
    }
}

/**
 * Debug-only switch: while [policy] is [Policy.Checked], every driver built afterwards gets a
 * [LoopGuard.Checking] reporting to its log, and its quiche calls are made through a [GuardedQuicheApi].
 * Off in production and everywhere a test has not turned it on.
 */
internal object DebugLoopGuard {
    sealed interface Policy {
        data object Off : Policy

        class Checked(
            val log: ViolationLog,
        ) : Policy
    }

    private val current = AtomicReference<Policy>(Policy.Off)

    var policy: Policy
        get() = current.load()
        set(value) = current.store(value)

    fun newGuard(): LoopGuard =
        when (val p = policy) {
            Policy.Off -> LoopGuard.Unchecked
            is Policy.Checked -> LoopGuard.Checking(p.log)
        }
}

/** Runs [block], a suspension point of the loop, outside the loop's turn. */
internal inline fun <T> LoopGuard.suspending(block: () -> T): T {
    suspendTurn()
    try {
        return block()
    } finally {
        resumeTurn()
    }
}

/** The id of the calling thread, for comparing two callers within one turn. */
internal expect fun currentThreadId(): Long
