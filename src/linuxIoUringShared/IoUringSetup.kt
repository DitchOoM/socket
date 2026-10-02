package com.ditchoom.socket.iouring

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.ENOMEM
import platform.posix.strerror

// The flag values are the kernel's `IORING_SETUP_*` ABI.
private const val IORING_SETUP_COOP_TASKRUN = 0x100u
private const val IORING_SETUP_SINGLE_ISSUER = 0x1000u
private const val IORING_SETUP_DEFER_TASKRUN = 0x2000u

/** The flag sets ring setup tries, best first; the last asks for nothing a 5.1 kernel lacks. */
internal val IO_URING_SETUP_LADDER: List<UInt> =
    listOf(
        IORING_SETUP_SINGLE_ISSUER or IORING_SETUP_COOP_TASKRUN or IORING_SETUP_DEFER_TASKRUN,
        IORING_SETUP_SINGLE_ISSUER or IORING_SETUP_COOP_TASKRUN,
        IORING_SETUP_SINGLE_ISSUER,
        0u,
    )

/**
 * Passes over [IO_URING_SETUP_LADDER] while the kernel answers ENOMEM: eleven doubling waits, 2.047 s in
 * all — more than twice the longest refusal measured (see [setUpIoUring]).
 */
internal const val ENOMEM_SETUP_PASSES = 12

/** The wait before the second pass, doubling before each later one. */
internal const val ENOMEM_BACKOFF_BASE_MICROS = 1_000

/** One `io_uring_setup` the kernel refused. */
internal data class RefusedSetup(
    val pass: Int,
    val flags: UInt,
    val errno: Int,
)

/** What [setUpIoUring] came to. */
internal sealed interface RingSetup {
    /** The kernel accepted one of the flag sets; the ring is initialized. */
    data object Created : RingSetup

    /** Every attempt was refused. [final] is the refusal that ended it, [earlier] the ones before it. */
    data class Refused(
        val final: RefusedSetup,
        val earlier: List<RefusedSetup>,
    ) : RingSetup {
        @OptIn(ExperimentalForeignApi::class)
        fun describe(): String =
            (earlier + final).joinToString("; ") {
                "try=${it.pass} flags=0x${it.flags.toString(16)} -> errno=${it.errno} " +
                    "(${strerror(it.errno)?.toKString() ?: "?"})"
            }
    }
}

/**
 * Initialize an io_uring ring through [queueInit] (`io_uring_queue_init_params` with the given setup
 * flags, answering `>= 0` or `-errno`), walking [IO_URING_SETUP_LADDER] and retrying the whole ladder
 * with a doubling [sleepMicros] while the kernel answers ENOMEM.
 *
 * ENOMEM here is the user's RLIMIT_MEMLOCK, not an exhausted machine: the kernel charges every ring's
 * pages to the user (`io_create_region` → `__io_account_mem`) and returns them only when it frees the
 * ring's context, asynchronously, after the fd is closed. The charge is per user, so every process of
 * that user — sibling test binaries, the other module's ring — draws on the same budget. Measured on
 * kernel 6.18 at an 8 MB limit: one process churning rings waits up to 36 ms for a refusal to clear,
 * three churning under full CPU load up to 0.85 s, and with the limit lifted none is ever refused. The
 * ring ledger reads `created == released, live = 0` when it happens, so only waiting clears it. Any
 * other errno is a lasting answer about this host and ends setup at once.
 */
internal fun setUpIoUring(
    sleepMicros: (Int) -> Unit,
    queueInit: (flags: UInt) -> Int,
): RingSetup {
    val refusals = ArrayList<RefusedSetup>()
    for (pass in 0 until ENOMEM_SETUP_PASSES) {
        if (pass > 0) sleepMicros(ENOMEM_BACKOFF_BASE_MICROS shl (pass - 1))
        for (flags in IO_URING_SETUP_LADDER) {
            val ret = queueInit(flags)
            if (ret >= 0) return RingSetup.Created
            refusals += RefusedSetup(pass, flags, -ret)
        }
        if (refusals.last().errno != ENOMEM) break
    }
    return RingSetup.Refused(refusals.last(), refusals.dropLast(1))
}
