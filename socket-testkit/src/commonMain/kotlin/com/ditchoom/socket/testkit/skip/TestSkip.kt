package com.ditchoom.socket.testkit.skip

/**
 * Why a test did not run.
 *
 * Exhaustive by construction: a suite cannot express "this test was skipped" without naming a
 * cause the repository has agreed is legitimate, and every cause carries the specifics needed to
 * tell a real environment gap from a bug that happens to look like one.
 *
 * The alternative — the free-form early `return` this replaces — is worse than untyped, because on
 * Kotlin/Native it is *invisible*: there is no `assume` on K/N, so the only skip available is
 * returning early, and the report records that as a **pass**. A suite that silently stopped running
 * and a suite that passed are the same green tick.
 */
sealed interface SkipReason {
    /** Stable identifier for this cause, used in the emitted marker. */
    val label: String

    /** The specifics — which native, which host, which fixture — never just "unavailable". */
    val detail: String

    /**
     * The platform's quiche native could not be loaded (JNI `UnsatisfiedLinkError` or the FFM
     * equivalent).
     *
     * On CI this should never happen: the pipeline builds the natives and every consuming lane
     * gets them as an artifact. That is exactly why those lanes set [REQUIRE_ALL_TESTS_ENV] — a
     * missing native there means the artifact plumbing broke, and it used to surface as ~38 test
     * classes quietly vanishing from a green run.
     */
    data class NativeLibraryUnavailable(
        override val detail: String,
    ) : SkipReason {
        override val label = "native-library-unavailable"
    }

    /**
     * Running under `simctl spawn --standalone`, whose working directory has no `testcerts/`, so
     * a suite that needs a server cert+key on the filesystem cannot stand up its loopback server.
     */
    data class SimulatorLacksFixtures(
        override val detail: String,
    ) : SkipReason {
        override val label = "simulator-lacks-fixtures"
    }

    /**
     * Running under `simctl spawn --standalone`, which launches the test binary *outside* the
     * simulator's `launchd_sim` service context, so the network daemons a connection needs are not
     * reachable.
     *
     * Distinct from [SimulatorLacksFixtures] on purpose: that one is a missing file and would be
     * fixed by bundling a cert, this one is a missing service context and would be fixed by running
     * the lane against a pre-booted simulator with `standalone = false`. Collapsing them into one
     * "simulator" reason would hide which fix applies.
     */
    data class SimulatorLacksNetworkServices(
        override val detail: String,
    ) : SkipReason {
        override val label = "simulator-lacks-network-services"
    }

    /** The platform does not implement the transport under test (e.g. TCP in a browser). */
    data class TransportUnavailable(
        override val detail: String,
    ) : SkipReason {
        override val label = "transport-unavailable"
    }

    /**
     * The host OS does not produce the behaviour being asserted, so the assertion is meaningless
     * rather than failing — e.g. the JVM on Windows maps connect-refused to different Iocp codes
     * than the POSIX errno the exception-mapping tests pin.
     */
    data class HostBehaviourDiffers(
        override val detail: String,
    ) : SkipReason {
        override val label = "host-behaviour-differs"
    }

    /** A deliberately opt-in lane (benchmark, deep fuzz sweep) that this run did not request. */
    data class OptInLaneNotRequested(
        override val detail: String,
    ) : SkipReason {
        override val label = "opt-in-lane-not-requested"
    }
}

/**
 * Set to `1` on any lane where a skip is a failure rather than an accommodation.
 *
 * Lanes that build their own natives and own a real filesystem (JVM, Android instrumented, Linux
 * K/N, macOS K/N) set this: nothing in [SkipReason] can legitimately fire there, so if one does,
 * the environment is broken and the run must go red. The simulator lanes deliberately leave it
 * unset — their skips are real and documented — but still emit [SKIP_MARKER], so the inventory is
 * greppable instead of invisible.
 */
const val REQUIRE_ALL_TESTS_ENV: String = "SOCKET_REQUIRE_ALL_TESTS"

/**
 * Prefix of the line every skip emits. Greppable from the test XML's `system-out`, which every
 * platform's runner captures and CI already uploads.
 */
const val SKIP_MARKER: String = "[TEST-SKIPPED]"

/**
 * Record that the calling suite did not run a test body, and decide whether that is tolerable.
 *
 * Always emits [SKIP_MARKER] so the skip appears in the run's artifacts. Throws when
 * [REQUIRE_ALL_TESTS_ENV] is set, which is what turns "silently green" into "red, with the reason
 * already in the message".
 */
fun Any.recordSkip(reason: SkipReason): Unit = recordSkip(this::class.simpleName ?: "<anonymous>", reason)

/** [recordSkip] for callers that are not a suite instance (top-level helpers, companions). */
fun recordSkip(
    site: String,
    reason: SkipReason,
) {
    val marker = skipMarker(site, reason)
    println(marker)
    if (testkitEnv(REQUIRE_ALL_TESTS_ENV) == "1") throw skipForbidden(marker)
}

/**
 * The emitted line, split out from [recordSkip] so its format is testable without an environment:
 * CI greps this shape out of the test XML, so it is a contract, not a debug string.
 */
internal fun skipMarker(
    site: String,
    reason: SkipReason,
): String = "$SKIP_MARKER site=$site reason=${reason.label} detail=${reason.detail}"

/** The failure [recordSkip] raises on a lane that forbids skipping. Split out for the same reason. */
internal fun skipForbidden(marker: String): AssertionError =
    AssertionError(
        "$marker\n" +
            "$REQUIRE_ALL_TESTS_ENV=1: this lane must run every test, so a skip is a failure. " +
            "Either the environment this lane promises is broken (missing native, missing " +
            "fixture), or the skip is legitimate here and the lane should stop setting " +
            "$REQUIRE_ALL_TESTS_ENV.",
    )

/**
 * Read an environment variable. The repository's first shared env reader — `qlogDir`,
 * `timeScaleEnv` and the various inline `getenv` copies are the same three lines per platform, and
 * can collapse onto this.
 */
internal expect fun testkitEnv(name: String): String?
