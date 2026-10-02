package com.ditchoom.socket.iouring

import com.ditchoom.socket.iouring.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// The one io_uring engine for every module that owns a ring (root :socket and :socket-udp). Each
// module's build copies this directory into its own package, rewriting `com.ditchoom.socket.iouring`
// to that package (which also selects the module's own cinterop), because two klibs declaring the
// same internal name cannot link into one binary. What differs per module is declared next to its
// copy: `IoUringFailure` (the typed exception a caller sees when no ring can be set up),
// `IO_URING_MODULE` and `IO_URING_POLLER_THREAD`. Root :socket's build also makes [IoUringManager]
// public, because :socket-quic-quiche submits through it.

/**
 * An operation the kernel holds, and when it expires.
 *
 * [cancelRequested] is set once the deadline has passed and an `io_uring_prep_cancel64` has been
 * submitted for it. The op stays in `pendingOps` until the kernel produces its CQE (`-ECANCELED`, or
 * the real result if it raced in): a buffer handed to io_uring is freed by the caller only after the
 * kernel is done with it.
 */
private class PendingOperation(
    val deferred: CompletableDeferred<Int>,
    val deadline: TimeSource.Monotonic.ValueTimeMark?,
    var cancelRequested: Boolean = false,
)

/** Whether a caller waits on a submission's result. */
internal enum class SubmissionKind {
    /** A caller suspends on its deferred. */
    Awaited,

    /**
     * Nobody waits: a cancel aimed at an operation already submitted. It can only act on an operation in
     * flight in the life it was sent to, or queued ahead of it.
     */
    FireAndForget,
}

/** An operation for the event loop to put on the ring, sent from any thread. */
@OptIn(ExperimentalForeignApi::class)
internal class SubmissionRequest(
    val userData: Long,
    val deferred: CompletableDeferred<Int>,
    val deadline: TimeSource.Monotonic.ValueTimeMark?,
    val kind: SubmissionKind,
    val prepareOp: (sqe: CPointer<io_uring_sqe>, userData: Long) -> Unit,
)

/** The `io_uring_queue_init_params` call ring setup makes, given a ring and params whose flags are set. */
@OptIn(ExperimentalForeignApi::class)
internal typealias QueueInit = (CPointer<io_uring>, CPointer<io_uring_params>) -> Int

/** Where the event loop is in its life. Advanced only by compare-and-set. */
internal sealed interface PollerState {
    /** No event loop; the next submission starts one. */
    data object Idle : PollerState

    /**
     * One event loop's life, from its start until the state moves off this instance. Every start is a
     * new instance, so a stop or a withdrawal names the life it ends and can never end a later one.
     */
    class Running : PollerState {
        /** This life's submissions. Closed only after the state has moved off this instance. */
        val queue = Channel<SubmissionRequest>(Channel.UNLIMITED)

        /** Completed once this life's loop has released the ring, the eventfd and every waiter. */
        val ended = CompletableDeferred<Unit>()

        /** A last-socket close's request that this life release its ring if idle. */
        val idleRelease = AtomicReference<IdleReleaseSlot>(IdleReleaseSlot.None)

        override fun toString(): String = "Running"
    }
}

/** A life's answer to an idle-release request. */
internal enum class IdleRelease {
    /** Nothing was in flight and no socket was open: the life ended and released its ring. */
    Released,

    /** An operation was in flight or a socket was open: the life keeps running. */
    Refused,
}

/** A life's slot for an idle-release request. */
internal sealed interface IdleReleaseSlot {
    /** No request is waiting. */
    data object None : IdleReleaseSlot

    /** A request waits for the loop's [answer]. */
    class Asked(
        val answer: CompletableDeferred<IdleRelease>,
    ) : IdleReleaseSlot

    /** The life has ended; a request now would find no loop to answer it. */
    data object Ended : IdleReleaseSlot
}

/**
 * How a life's loop ended, which decides what its end owes the submissions it leaves behind.
 *
 * Never held in a `var` read in `finally`: Kotlin/Native smart-cast such a read to the variable's
 * initial type even after the loop reassigned it, and the cast then threw ClassCastException.
 */
private sealed interface LoopExit {
    /** Stopped, or its ring was refused: whatever is queued or in flight fails. */
    data object Stopped : LoopExit

    /**
     * It released an idle ring: nothing was in flight, so nothing fails, and anything queued after the
     * decision belongs to the next life.
     */
    class Idle(
        val answer: CompletableDeferred<IdleRelease>,
    ) : LoopExit
}

/** user_data reserved for the eventfd wakeup poll's CQEs. */
private const val EVENTFD_USER_DATA = 0L

/**
 * The process-wide io_uring ring and the single event loop that owns it.
 *
 * - One worker thread owns the ring; nothing else touches it.
 * - Callers hand operations to the running loop's queue, a lock-free channel, and an eventfd wakes the
 *   loop when it is sleeping in `io_uring_wait_cqe_timeout`.
 * - The loop's life is a [PollerState] advanced by compare-and-set: no lock anywhere, on the hot path
 *   or on start and stop.
 * - The last socket to close asks the loop to release the ring if it is idle (see [releaseIfIdle]); the
 *   next operation starts a new one. [cleanup] stops it unconditionally. The worker thread is kept for
 *   the process lifetime.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private val ringRef = AtomicReference<CPointer<io_uring>?>(null)

    /** Process-wide ring depth. */
    private val queueDepth: Int = 1024

    /** How long the loop sleeps when no operation has a deadline. */
    private val DEFAULT_POLL_TIMEOUT = 1.seconds

    /** user_data source; starts at 1 because 0 is [EVENTFD_USER_DATA]. */
    private val nextUserDataCounter = AtomicLong(1L)

    /** Open sockets. The last close asks the loop to release the ring once it is idle. */
    private val activeSocketCount = AtomicInt(0)

    /** Sockets currently counted as open; the last one's close releases the ring. */
    internal val activeSockets: Int get() = activeSocketCount.value

    private val state = AtomicReference<PollerState>(PollerState.Idle)

    /** The eventfd the loop sleeps on, or -1 while none is open. */
    private val wakeupFd = AtomicInt(-1)

    /** 1 while the loop is about to sleep or sleeping in `io_uring_wait_cqe_timeout`. */
    private val pollerSleeping = AtomicInt(0)

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val pollerDispatcherRef = AtomicReference<CloseableCoroutineDispatcher?>(null)
    private val pollerScopeRef = AtomicReference<CoroutineScope?>(null)

    /** Called when a socket is opened. */
    fun onSocketOpened() {
        activeSocketCount.incrementAndGet()
    }

    /** Called when a socket is closed; the last close asks the loop to release the ring if idle. */
    fun onSocketClosed() {
        if (activeSocketCount.decrementAndGet() <= 0) {
            // Back to 0 after a double close's underflow.
            activeSocketCount.compareAndSet(-1, 0)
            releaseIfIdle(state.value)
        }
    }

    /**
     * Asks [observed], the life the last close saw, to release its ring if it is idle, and blocks until it
     * answers. Unlike [stop], this never fails an operation: only the loop knows what is in flight — a
     * connect is not counted as a socket until it completes, and a socket can open after the count reached
     * zero — so an operation in flight or a socket open makes it refuse and keep running, and a later last
     * close asks again.
     */
    internal fun releaseIfIdle(observed: PollerState) {
        if (observed !is PollerState.Running || activeSocketCount.value > 0) return
        val answer = CompletableDeferred<IdleRelease>()
        // A request already waiting is answered for both; an ended life has nothing to release.
        if (!observed.idleRelease.compareAndSet(IdleReleaseSlot.None, IdleReleaseSlot.Asked(answer))) return
        writeWakeup()
        if (runBlocking { answer.await() } == IdleRelease.Released) {
            runBlocking { observed.ended.await() }
        }
    }

    /** The loop's life right now. */
    internal val pollerState: PollerState get() = state.value

    /**
     * Worker threads this manager has created. The worker is kept across [cleanup], so a start/stop
     * cycle must not add one.
     */
    internal val pollerDispatchersCreated = AtomicInt(0)

    /**
     * Rings created and released over the process lifetime. `created - released` is the number alive:
     * every ring is charged to the process until `io_uring_queue_exit`, so this ledger is what reads an
     * `io_uring_setup` `ENOMEM` as a leak or as a budget exhausted by something else.
     */
    internal val ringsCreated = AtomicInt(0)
    internal val ringsReleased = AtomicInt(0)

    /** Cancel SQEs submitted by [cancelOperation]. */
    internal val cancelSubmitCount = AtomicInt(0)

    /** Cancel SQEs submitted because an operation's deadline passed. */
    internal val timeoutCancelSubmitCount = AtomicInt(0)

    /**
     * The `io_uring_queue_init_params` call ring setup makes. A test substitutes one that refuses, to
     * drive the setup-failure path without a kernel.
     */
    internal val queueInit =
        AtomicReference<QueueInit> { ring, params ->
            io_uring_queue_init_params(queueDepth.toUInt(), ring, params)
        }

    /**
     * Runs on the event loop thread the moment a life has granted an idle release, before its queue
     * closes. A test uses it to send into that window, as a caller that read the life just before the
     * state moved off it would.
     */
    internal val idleReleaseGranted = AtomicReference<(PollerState.Running) -> Unit> { }

    /** This manager's state plus the host's, for a failure report. Failure path only. */
    internal fun diagnosticSnapshot(): String =
        buildString {
            appendLine("io_uring manager ($IO_URING_MODULE):")
            appendLine(
                "  rings: created=${ringsCreated.value} released=${ringsReleased.value} " +
                    "live=${ringsCreated.value - ringsReleased.value} queueDepth=$queueDepth",
            )
            appendLine(
                "  poller: ${state.value} sleeping=${pollerSleeping.value} " +
                    "workersCreated=${pollerDispatchersCreated.value} activeSockets=${activeSocketCount.value}",
            )
            append(ioUringHostReport())
        }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun getOrCreatePollerDispatcher(): CloseableCoroutineDispatcher {
        pollerDispatcherRef.value?.let { return it }
        val newDispatcher = newSingleThreadContext(IO_URING_POLLER_THREAD)
        if (pollerDispatcherRef.compareAndSet(null, newDispatcher)) {
            pollerDispatchersCreated.incrementAndGet()
        } else {
            newDispatcher.close()
        }
        return pollerDispatcherRef.value!!
    }

    private fun getOrCreatePollerScope(): CoroutineScope {
        pollerScopeRef.value?.let { return it }
        val dispatcher = getOrCreatePollerDispatcher()
        val newScope = CoroutineScope(dispatcher + SupervisorJob())
        if (!pollerScopeRef.compareAndSet(null, newScope)) {
            newScope.cancel()
        }
        return pollerScopeRef.value!!
    }

    /**
     * The ring, created through [setUpIoUring] if there is none yet.
     *
     * MUST be called on the event loop thread (SINGLE_ISSUER binds the ring to its creating thread).
     */
    private fun initRing(): CPointer<io_uring> {
        ringRef.value?.let { return it }

        val ptr = nativeHeap.alloc<io_uring>().ptr
        val params = nativeHeap.alloc<io_uring_params>()
        val init = queueInit.value
        val setup =
            setUpIoUring({ micros -> usleep(micros.toUInt()) }) { flags ->
                memset(params.ptr, 0, sizeOf<io_uring_params>().convert())
                params.flags = flags
                init(ptr, params.ptr)
            }
        nativeHeap.free(params)
        return when (setup) {
            RingSetup.Created -> {
                ringsCreated.incrementAndGet()
                ringRef.value = ptr
                ptr
            }
            is RingSetup.Refused -> {
                nativeHeap.free(ptr)
                val refused = setup.final.errno
                throw IoUringFailure(
                    "Failed to initialize io_uring: ${strerror(refused)?.toKString() ?: "Unknown error"} " +
                        "(errno=$refused). $IO_URING_MODULE requires Linux kernel 5.1+ with io_uring support. " +
                        "Attempts: ${setup.describe()}\n" +
                        diagnosticSnapshot(),
                )
            }
        }
    }

    /** The ring the running loop owns. */
    fun getRing(): CPointer<io_uring> = ringRef.value ?: throw IoUringFailure("IoUringManager not initialized")

    /** Opens the eventfd and arms a multi-shot poll on it. On the event loop thread. */
    private fun setupEventfd(ring: CPointer<io_uring>) {
        val fd = eventfd(0u, EFD_NONBLOCK)
        if (fd < 0) {
            throw IoUringFailure("Failed to create eventfd: errno=$errno")
        }
        wakeupFd.value = fd

        val sqe =
            io_uring_get_sqe(ring)
                ?: throw IoUringFailure("Failed to get SQE for eventfd poll registration")
        io_uring_prep_poll_multishot(sqe, fd, POLLIN.toUInt())
        io_uring_sqe_set_data64(sqe, EVENTFD_USER_DATA.toULong())
        io_uring_submit(ring)
    }

    /** Wakes the loop if it is sleeping: one `eventfd_write`, skipped while it is awake. */
    private fun wakePoller() {
        if (pollerSleeping.value == 1) writeWakeup()
    }

    private fun writeWakeup() {
        val fd = wakeupFd.value
        if (fd >= 0) {
            memScoped {
                val buf = alloc<eventfd_tVar>()
                buf.value = 1u
                eventfd_write(fd, buf.value)
            }
        }
    }

    /** Resets the eventfd counter after a wakeup. On the event loop thread. */
    private fun drainEventfd() {
        val fd = wakeupFd.value
        if (fd >= 0) {
            memScoped {
                val buf = alloc<eventfd_tVar>()
                eventfd_read(fd, buf.ptr)
            }
        }
    }

    /** The running loop's life, starting one if there is none. */
    private fun running(): PollerState.Running {
        while (true) {
            when (val current = state.value) {
                is PollerState.Running -> return current
                PollerState.Idle -> {
                    val life = PollerState.Running()
                    if (state.compareAndSet(PollerState.Idle, life)) {
                        launchLoop(life)
                        return life
                    }
                }
            }
        }
    }

    /** Runs [life]'s loop on the worker thread, after any loop still running there has returned. */
    private fun launchLoop(life: PollerState.Running) {
        getOrCreatePollerScope().launch {
            try {
                eventLoop(life)
            } finally {
                life.ended.complete(Unit)
            }
        }
    }

    /** Hands [request] to the running loop, starting one if there is none. */
    private fun enqueue(request: SubmissionRequest) {
        while (true) {
            // A closed queue belongs to a life the state is moving off, so a later read sees its
            // successor or Idle.
            if (running().queue.trySend(request).isSuccess) {
                wakePoller()
                return
            }
        }
    }

    /**
     * Ends [life]: the state moves off it unless it already has, its idle-release slot closes (answering
     * a request still waiting with [IdleRelease.Released], since the life ends either way), its queue
     * closes, and every submission still queued for it is handed to [fail].
     */
    private inline fun endLife(
        life: PollerState.Running,
        fail: (CompletableDeferred<Int>) -> Unit,
    ) {
        closeLife(life)
        while (true) {
            val request = life.queue.tryReceive().getOrNull() ?: break
            fail(request.deferred)
        }
    }

    private fun closeLife(life: PollerState.Running) {
        state.compareAndSet(life, PollerState.Idle)
        when (val asked = life.idleRelease.getAndSet(IdleReleaseSlot.Ended)) {
            is IdleReleaseSlot.Asked -> asked.answer.complete(IdleRelease.Released)
            IdleReleaseSlot.None, IdleReleaseSlot.Ended -> Unit
        }
        life.queue.close()
    }

    /**
     * Hands the idle-releasing [life] on. Its queue is closed first, while the state is still [life], so
     * what is left in it is final: a sender that reads [life] now finds the queue closed and retries until
     * the state has moved. A submission still queued was sent after the loop's last drain and never
     * reached a ring. If one is still awaited, they all go, in order, into a successor's queue before the
     * successor is published, so nothing sent later — a cancel naming one of them included — can reach a
     * ring ahead of them. Otherwise the state goes idle: a fire-and-forget left over can only aim at an
     * operation in flight here — there are none — or queued ahead of it, and nothing is.
     *
     * Returns how the loop exits: [LoopExit.Stopped] when a [stop] moved the state off [life] first, in
     * which case the leftovers are failed as a stopped life's are.
     */
    private fun handOnIdle(
        life: PollerState.Running,
        answer: CompletableDeferred<IdleRelease>,
    ): LoopExit {
        life.queue.close()
        val leftover = ArrayList<SubmissionRequest>()
        while (true) leftover += life.queue.tryReceive().getOrNull() ?: break
        val next: PollerState =
            if (leftover.any { it.kind == SubmissionKind.Awaited && !it.deferred.isCompleted }) {
                PollerState.Running().also { successor -> leftover.forEach { successor.queue.trySend(it) } }
            } else {
                PollerState.Idle
            }
        if (!state.compareAndSet(life, next)) {
            leftover.forEach { it.deferred.complete(-ECANCELED) }
            // The request was taken off the slot, so only this answers it; the life ends either way.
            answer.complete(IdleRelease.Released)
            return LoopExit.Stopped
        }
        if (next is PollerState.Running) launchLoop(next)
        return LoopExit.Idle(answer)
    }

    /** Time until the earliest live deadline, or [DEFAULT_POLL_TIMEOUT]. On the event loop thread. */
    private fun calculateNextTimeout(pendingOps: HashMap<Long, PendingOperation>): Duration {
        var earliest: Duration? = null

        for ((_, op) in pendingOps) {
            val deadline = op.deadline ?: continue
            // Already cancel-requested: only its CQE is awaited. Counting it would peg the wait at
            // zero and spin until that CQE lands.
            if (op.cancelRequested) continue
            val remaining = -deadline.elapsedNow()
            if (earliest == null || remaining < earliest) {
                earliest = remaining
            }
        }

        return when {
            earliest == null -> DEFAULT_POLL_TIMEOUT
            earliest.isNegative() -> Duration.ZERO
            else -> earliest
        }
    }

    /**
     * Submits an `io_uring_prep_cancel64` for every operation whose deadline has passed. The operation
     * stays in [pendingOps] until the kernel delivers its CQE, because the kernel may still hold its
     * buffer: completing it here would let the caller free memory a later datagram is written into.
     * On the event loop thread. True if any cancel SQE was prepared (the caller submits).
     */
    private fun processExpiredOperations(
        ring: CPointer<io_uring>,
        pendingOps: HashMap<Long, PendingOperation>,
    ): Boolean {
        var submittedCancel = false
        for ((userData, op) in pendingOps) {
            val deadline = op.deadline ?: continue
            if (op.cancelRequested || op.deferred.isCompleted) continue
            if (!deadline.hasPassedNow()) continue
            val sqe = io_uring_get_sqe(ring) ?: break // ring full — retry next iteration
            io_uring_prep_cancel64(sqe, userData.toULong(), 0)
            // The cancel's own CQE carries a fresh userData, which no pending op claims; the original
            // op's CQE completes the deferred.
            io_uring_sqe_set_data64(sqe, nextUserData().toULong())
            op.cancelRequested = true
            timeoutCancelSubmitCount.incrementAndGet()
            submittedCancel = true
        }
        return submittedCancel
    }

    /** Moves [queue]'s submissions onto the ring. On the event loop thread. True if any SQE was prepared. */
    private fun drainSubmissionChannel(
        ring: CPointer<io_uring>,
        queue: Channel<SubmissionRequest>,
        pendingOps: HashMap<Long, PendingOperation>,
    ): Boolean {
        var submitted = false
        while (true) {
            val request = queue.tryReceive().getOrNull() ?: break

            // Already completed (e.g. its caller was cancelled): nothing to submit.
            if (request.deferred.isCompleted) continue

            pendingOps[request.userData] = PendingOperation(request.deferred, request.deadline)

            val sqe = io_uring_get_sqe(ring)
            if (sqe == null) {
                // Ring full: the caller sees a failure.
                request.deferred.complete(-EBUSY)
                pendingOps.remove(request.userData)
                continue
            }

            request.prepareOp(sqe, request.userData)
            io_uring_sqe_set_data64(sqe, request.userData.toULong())
            submitted = true
        }
        return submitted
    }

    /**
     * One life's event loop, on the worker thread. It runs while the state is [life]: drain the queue
     * onto the ring, submit, sleep until a CQE, a deadline or a wakeup, complete the waiters, cancel
     * what expired. A ring that cannot be set up ends the life with every queued submission failed by
     * the module's [IoUringFailure].
     */
    private fun eventLoop(life: PollerState.Running) {
        // Stopped before this life reached the thread: nothing to set up.
        if (state.value !== life) {
            endLife(life) { it.complete(-ECANCELED) }
            return
        }
        val ring =
            try {
                initRing().also(::setupEventfd)
            } catch (e: IoUringFailure) {
                endLife(life) { it.completeExceptionally(e) }
                return
            }

        // Only this thread touches it.
        val pendingOps = HashMap<Long, PendingOperation>()

        val cqePtr = nativeHeap.alloc<CPointerVar<io_uring_cqe>>()
        val ts = nativeHeap.alloc<__kernel_timespec>()

        val exit = AtomicReference<LoopExit>(LoopExit.Stopped)
        try {
            while (state.value === life) {
                // 0. A last-socket close asked this life to release its ring if idle. What is queued
                //    now was sent before the question, so it is taken in before the answer.
                when (val asked = life.idleRelease.getAndSet(IdleReleaseSlot.None)) {
                    IdleReleaseSlot.None, IdleReleaseSlot.Ended -> Unit
                    is IdleReleaseSlot.Asked -> {
                        if (drainSubmissionChannel(ring, life.queue, pendingOps)) {
                            io_uring_submit(ring)
                        }
                        if (pendingOps.isEmpty() && activeSocketCount.value == 0 && state.value === life) {
                            idleReleaseGranted.value(life)
                            exit.value = handOnIdle(life, asked.answer)
                            break
                        }
                        asked.answer.complete(IdleRelease.Refused)
                    }
                }

                // 1. Mark sleeping BEFORE the drain: a submission that lands after the drain sees the
                //    flag and writes the eventfd, which wakes step 4.
                pollerSleeping.value = 1

                // 2. Queue → SQEs.
                val hasNewSubmissions = drainSubmissionChannel(ring, life.queue, pendingOps)

                // 3. One submit for the batch.
                if (hasNewSubmissions) {
                    io_uring_submit(ring)
                }

                // 4. Sleep until the earliest deadline, a CQE, or an eventfd wakeup.
                val timeout = calculateNextTimeout(pendingOps)
                ts.tv_sec = timeout.inWholeSeconds
                ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)
                val waitRet = io_uring_wait_cqe_timeout(ring, cqePtr.ptr, ts.ptr)
                pollerSleeping.value = 0

                // 5. Cancel whatever expired.
                if (processExpiredOperations(ring, pendingOps)) {
                    io_uring_submit(ring)
                }

                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    continue
                }
                if (waitRet < 0) {
                    // EINTR or another error: the next iteration drains again.
                    continue
                }

                // 6. Complete the waiters.
                do {
                    val cqeVal = cqePtr.value ?: break
                    val userData = io_uring_cqe_get_data64(cqeVal).toLong()
                    val result = cqeVal.pointed.res
                    val flags = cqeVal.pointed.flags
                    io_uring_cqe_seen(ring, cqeVal)

                    if (userData == EVENTFD_USER_DATA) {
                        drainEventfd()
                        // Without IORING_CQE_F_MORE the multi-shot poll has ended; re-arm it.
                        if (flags.toInt() and IORING_CQE_F_MORE.toInt() == 0) {
                            val pollSqe = io_uring_get_sqe(ring)
                            if (pollSqe != null) {
                                val fd = wakeupFd.value
                                if (fd >= 0) {
                                    io_uring_prep_poll_multishot(pollSqe, fd, POLLIN.toUInt())
                                    io_uring_sqe_set_data64(pollSqe, EVENTFD_USER_DATA.toULong())
                                    io_uring_submit(ring)
                                }
                            }
                        }
                        continue
                    }

                    val op = pendingOps.remove(userData)
                    if (op != null && !op.deferred.isCompleted) {
                        // A timeout-cancelled op reports -ETIMEDOUT, unless data arrived before the
                        // cancel landed (result >= 0). The deferred completes only now, on the CQE,
                        // so the caller frees its buffer only after the kernel is done with it.
                        val toComplete = if (op.cancelRequested && result < 0) -ETIMEDOUT else result
                        op.deferred.complete(toComplete)
                    }
                } while (io_uring_peek_cqe(ring, cqePtr.ptr) >= 0)
            }
        } finally {
            nativeHeap.free(ts)
            nativeHeap.free(cqePtr)

            // Empty on an idle exit: the release was granted only with nothing in flight.
            pendingOps.values.forEach { it.deferred.complete(-ECANCELED) }
            pendingOps.clear()

            // The eventfd and the ring are torn down on this thread, never under a concurrent
            // io_uring_wait_cqe_timeout.
            val fd = wakeupFd.getAndSet(-1)
            if (fd >= 0) close(fd)

            val ptr = ringRef.getAndSet(null)
            ptr?.let {
                io_uring_queue_exit(it)
                nativeHeap.free(it)
                ringsReleased.incrementAndGet()
            }

            when (val ended = exit.value) {
                LoopExit.Stopped -> endLife(life) { it.complete(-ECANCELED) }
                is LoopExit.Idle -> {
                    closeLife(life)
                    ended.answer.complete(IdleRelease.Released)
                }
            }
        }
    }

    /** A fresh user_data value. */
    fun nextUserData(): Long = nextUserDataCounter.incrementAndGet()

    /**
     * Submits an operation and suspends until its CQE. [prepareOp] runs on the event loop thread.
     * Returns the CQE result: `>= 0` on success, `-errno` on failure. If no ring can be set up, throws
     * the module's [IoUringFailure].
     *
     * On cancellation the kernel operation is cancelled and its CQE awaited (bounded) before the
     * CancellationException propagates, so the kernel no longer holds the caller's buffers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun submitAndWait(
        timeout: Duration? = null,
        prepareOp: (sqe: CPointer<io_uring_sqe>, userData: Long) -> Unit,
    ): Int {
        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }
        enqueue(SubmissionRequest(userData, deferred, deadline, SubmissionKind.Awaited, prepareOp))
        return awaitCompletion(userData, deferred)
    }

    /**
     * A user_data value and its deferred for an operation submitted later with [submitRegistered], so
     * the caller knows the user_data (to cancel it) before the submission. Starts the loop.
     */
    fun registerOperation(timeout: Duration? = null): Pair<Long, CompletableDeferred<Int>> {
        running()
        return nextUserData() to CompletableDeferred()
    }

    /** Submits an operation registered with [registerOperation]; otherwise as [submitAndWait]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun submitRegistered(
        userData: Long,
        deferred: CompletableDeferred<Int>,
        timeout: Duration? = null,
        prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit,
    ): Int {
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }
        enqueue(SubmissionRequest(userData, deferred, deadline, SubmissionKind.Awaited) { sqe, _ -> prepareOp(sqe) })
        return awaitCompletion(userData, deferred)
    }

    private suspend fun awaitCompletion(
        userData: Long,
        deferred: CompletableDeferred<Int>,
    ): Int {
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            submitNoWaitUnsafe { sqe ->
                io_uring_prep_cancel64(sqe, userData.toULong(), 0)
            }
            withContext(NonCancellable) {
                try {
                    withTimeout(100) { deferred.await() }
                } catch (_: Exception) {
                    // Timed out or failed: the kernel is done with the op either way.
                }
            }
            throw e
        }
    }

    /** Fire-and-forget submission to the running loop, as [submitNoWaitUnsafe]. */
    suspend fun submitNoWait(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        submitNoWaitUnsafe(prepareOp)
    }

    /**
     * Fire-and-forget submission to the running loop; dropped when no loop is running, since then no
     * operation is in flight for it to act on. Non-suspending, for cancellation handlers.
     */
    fun submitNoWaitUnsafe(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        val request =
            SubmissionRequest(nextUserData(), CompletableDeferred(), null, SubmissionKind.FireAndForget) { sqe, _ ->
                prepareOp(sqe)
            }
        while (true) {
            val life = state.value as? PollerState.Running ?: return
            // A closed queue belongs to a life the state is moving off; what it held moves first, so the
            // next read sees the life this request must follow it into.
            if (life.queue.trySend(request).isSuccess) {
                wakePoller()
                return
            }
        }
    }

    /**
     * Cancels the in-flight operation [userData], so a parked recv completes promptly with
     * `-ECANCELED` rather than running out its timeout. The kernel matches by user_data, not fd, so
     * this is safe after the fd is closed or reused. Non-suspending; a no-op when no loop is running.
     */
    fun cancelOperation(userData: Long) {
        if (userData == 0L) return
        if (state.value !is PollerState.Running) return
        cancelSubmitCount.incrementAndGet()
        submitNoWaitUnsafe { sqe ->
            io_uring_prep_cancel64(sqe, userData.toULong(), 0)
        }
    }

    /**
     * Stops the running loop whatever it holds, and blocks until it has released the ring and the eventfd;
     * queued and in-flight operations complete with `-ECANCELED`. A last-socket close uses
     * [releaseIfIdle] instead, which never fails an operation. The next operation starts a new loop. The
     * worker thread is kept: closing its dispatcher blocks until the worker's own park expires, and an
     * idle worker does not keep the process alive.
     */
    fun cleanup() {
        stop(state.value)
    }

    /**
     * Stops [observed], the life a caller saw. Only that life: if it has already ended (stopped, or
     * withdrawn because its ring could not be set up), a loop started since is not this call's to stop.
     */
    internal fun stop(observed: PollerState) {
        if (observed !is PollerState.Running) return
        state.compareAndSet(observed, PollerState.Idle)
        // The loop checks the state before every sleep; this cuts a sleep already begun short.
        writeWakeup()
        runBlocking { observed.ended.await() }
    }
}
