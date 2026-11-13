package com.ditchoom.socket

import com.ditchoom.socket.nio.util.LoopMailbox
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The enqueue / flag / wake-up / sleep protocol of a selector loop ([LoopMailbox]), model checked without a
 * `Selector`: what a selector's blocking call does is modelled by [sleep], where `wakeup()` before the call
 * makes the call return at once, as the JDK specifies. The property: the loop never ends a turn asleep with
 * an item queued and no wake-up pending (a lost wake-up would strand a command and hang its caller).
 */
abstract class LoopMailboxHarness(
    private val postsAsLoopThread: Boolean,
) {
    private val sleep = AtomicInteger(IDLE)
    private val mailbox = LoopMailbox<Int>(wake = { sleep.updateAndGet { if (it == SLEEPING) IDLE else PERMIT } })

    @Operation
    fun post() = if (postsAsLoopThread) mailbox.postFromLoopThread(1) else mailbox.post(1)

    @Operation(nonParallelGroup = LOOP)
    fun loopTurn() {
        mailbox.drain { }
        if (mailbox.prepareToSleep()) sleep.updateAndGet { if (it == PERMIT) IDLE else SLEEPING }
    }

    @Validate
    fun noLostWakeup() {
        check(!(sleep.get() == SLEEPING && !mailbox.isEmpty())) { "an item is queued and the loop sleeps with no wake-up pending" }
    }

    private companion object {
        const val LOOP = "loop"
        const val IDLE = 0
        const val PERMIT = 1
        const val SLEEPING = 2
    }
}

class LoopMailboxModel : LoopMailboxHarness(postsAsLoopThread = false)

/** The control: a poster that wrongly claims to be the loop thread never wakes it, so an item is stranded. */
class UnwakingMailboxModel : LoopMailboxHarness(postsAsLoopThread = true)

private fun mailboxOptions(): ModelCheckingOptions =
    ModelCheckingOptions()
        .threads(3)
        .actorsPerThread(3)
        .actorsBefore(0)
        .actorsAfter(0)
        .iterations(30)
        .invocationsPerIteration(1000)

class LoopMailboxLincheckTest {
    @Test
    fun noItemIsStrandedAcrossTheLoopsSleep() {
        mailboxOptions().check(LoopMailboxModel::class.java)
    }

    @Test
    fun theControlStrandsAnItem() {
        val failure = assertFailsWith<LincheckAssertionError> { mailboxOptions().check(UnwakingMailboxModel::class.java) }
        assertTrue("an item is queued and the loop sleeps" in failure.message.orEmpty(), failure.message)
    }
}
