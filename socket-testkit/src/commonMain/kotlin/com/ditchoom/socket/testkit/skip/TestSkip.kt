package com.ditchoom.socket.testkit.skip

import kotlin.reflect.KClass

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
 * K/N, macOS K/N) set this: a skip whose cause the lane *provisions* — a native, a fixture — cannot
 * legitimately fire there, so if one does, the environment is broken and the run must go red. The
 * simulator lanes deliberately leave it unset — their skips are real and documented — but still emit
 * [SKIP_MARKER], so the inventory is greppable instead of invisible.
 *
 * What it does **not** promise is that every test is runnable on every host: a lane cannot provision
 * a capability the host does not have. Those skips say so in their [SkipGate] and this variable
 * leaves them alone.
 */
const val REQUIRE_ALL_TESTS_ENV: String = "SOCKET_REQUIRE_ALL_TESTS"

/**
 * Which promise decides whether a recorded skip is also a failure.
 *
 * Two gates rather than one because [REQUIRE_ALL_TESTS_ENV] is a statement about the *lane* ("I
 * provisioned everything these tests need"), and not every skip is about provisioning. A host
 * capability the lane cannot install no matter how it is configured — an unprivileged macOS runner
 * cannot bind the 127.0.0.2 loopback alias, a Windows host does not produce POSIX errnos — makes
 * that promise unsatisfiable, and a gate that cannot be satisfied is not a gate: the lane either
 * stays red forever or stops gating the ~38 suites it was protecting.
 *
 * Measured, not hypothesised: the macOS quiche jvmTest lanes went red on exactly this, one
 * `HostBehaviourDiffers` skip against a `SOCKET_REQUIRE_ALL_TESTS=1` lane.
 */
sealed interface SkipGate {
    /**
     * [REQUIRE_ALL_TESTS_ENV] decides. The default, and correct for every cause a lane provisions:
     * a missing native or fixture on a lane that promised to build one is a broken lane.
     */
    data object LaneMustRunEveryTest : SkipGate

    /**
     * The lane gate does not apply, because no lane setting could make this test run — [capability]
     * is missing from the *host*.
     *
     * Exempt from failing, never from being seen: the marker is still emitted and the CI inventory
     * still counts it, so "this host has quietly stopped being able to do X" stays visible. Callers
     * that own a narrower switch (`QUIC_MIGRATION_REQUIRE_RUN` on the lanes where the capability
     * *is* present) escalate it themselves, which keeps the escalation where the knowledge is.
     *
     * [capability] is required so the exemption reads as a reviewable claim about a host rather than
     * a bare opt-out of the gate.
     */
    data class HostCannotProvideIt(
        val capability: String,
    ) : SkipGate
}

/**
 * Prefix of the line every skip emits. Greppable from the test XML's `system-out`, which every
 * platform's runner captures and CI already uploads.
 */
const val SKIP_MARKER: String = "[TEST-SKIPPED]"

/**
 * Record that [site] did not run a test body, and decide whether that is tolerable.
 *
 * Always emits [SKIP_MARKER] so the skip appears in the run's artifacts. Throws when [gate] says
 * this lane promised to run the test and [REQUIRE_ALL_TESTS_ENV] is set, which is what turns
 * "silently green" into "red, with the reason already in the message".
 *
 * ## Why the site is named rather than inferred
 *
 * This was `Any.recordSkip`, deriving the site from `this::class.simpleName`. That reads as "the suite
 * I am in", and inside a lambda with a receiver it is not: an extension on `Any` binds to the
 * **innermost implicit receiver**, so a call in a `runBlocking { }` body recorded
 * `site=BlockingCoroutine` and one in `runTest { }` recorded `site=TestScopeImpl` — measured, at every
 * such call site. The inventory groups by site, so the one column naming *which* suite stopped running
 * named a coroutine implementation class instead.
 *
 * A [KClass] parameter cannot be captured by an enclosing lambda: the call site has to write the class
 * down, and the compiler checks and renames it.
 */
fun recordSkip(
    site: KClass<*>,
    reason: SkipReason,
    gate: SkipGate = SkipGate.LaneMustRunEveryTest,
): Unit = recordSkip(site.simpleName ?: "<anonymous>", reason, gate)

/** [recordSkip] for callers with no class to name (top-level helpers, generated or synthetic sites). */
fun recordSkip(
    site: String,
    reason: SkipReason,
    gate: SkipGate = SkipGate.LaneMustRunEveryTest,
) {
    val marker = skipMarker(site, reason)
    println(marker)
    if (skipIsForbidden(gate, testkitEnv(REQUIRE_ALL_TESTS_ENV))) throw skipForbidden(marker)
}

/**
 * The decision [recordSkip] makes, as a pure function of the two things that decide it.
 *
 * Split out so the matrix is testable without an environment — the previous shape could only be
 * checked by running a whole lane and reading whether it went red, which is how a gate that fires on
 * a host that cannot satisfy it reached CI in the first place.
 */
internal fun skipIsForbidden(
    gate: SkipGate,
    requireAllTests: String?,
): Boolean =
    when (gate) {
        SkipGate.LaneMustRunEveryTest -> requireAllTests == "1"
        is SkipGate.HostCannotProvideIt -> false
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
