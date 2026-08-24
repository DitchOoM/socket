package com.ditchoom.socket.testkit

import com.ditchoom.socket.testkit.skip.testkitEnv
import kotlin.time.Duration

/** Environment variable read by [testTimeScale]. */
const val TEST_TIME_SCALE_ENV: String = "QUIC_TEST_TIME_SCALE"

/**
 * Multiplier applied to test **deadlines and backstops** — per-op `withTimeout` budgets, wall-clock
 * caps, settle waits and idle-timeout values. It lets a loaded CI runner (set e.g.
 * `QUIC_TEST_TIME_SCALE=3`) get proportionally more wall-clock without changing any test's *logic*.
 *
 * Always `>= 1.0` (clamped to `[1.0, 10.0]`): tests only ever get *more* time, never less, so
 * up-scaling can never make an assertion vacuous or weaken a timing relationship. Because the factor
 * is uniform, applying it to *every* duration in a suite preserves that suite's ratios exactly — "the
 * keepalive PING interval stays well under the idle timeout", "the idle timer fires before the read
 * backstop" — while granting absolute headroom. A malformed value falls back to `1.0`; an absurdly
 * large one is capped, so a typo cannot hang CI for hours.
 *
 * ## Why this lives in :socket-testkit
 *
 * :socket-testsuite has had this function for a while, but it cannot be the home for it: that module
 * `api`s :socket-http3, so :socket-http3 depending on **it** would be a cycle. :socket-testkit is the
 * shared ancestor both can see — the same reason the Apple simulator skip gate moved here rather than
 * being hand-mirrored. :socket-testsuite's [testTimeScale] now delegates to this one, so the env var
 * name and the clamp have a single definition instead of two that can drift apart.
 */
fun testTimeScale(): Double = parseTimeScale(testkitEnv(TEST_TIME_SCALE_ENV))

/**
 * The whole of [testTimeScale] that can be wrong, split out from the environment read so it can be
 * tested without one. Absent, blank or unparseable reads 1.0 — a lane that fat-fingers the value gets
 * the unscaled suite it would have had anyway, never a crash and never a shortened deadline.
 */
internal fun parseTimeScale(raw: String?): Double = raw?.trim()?.toDoubleOrNull()?.coerceIn(1.0, 10.0) ?: 1.0

/**
 * Scale a deadline/backstop [Duration] by [testTimeScale].
 *
 * Apply to `withTimeout` budgets, settle waits and connect deadlines — anywhere a slower runner
 * should simply be given more wall-clock.
 *
 * A negotiated protocol timer (QUIC's `idleTimeout`) counts too, but only when **both ends scale
 * together**: an in-process loopback suite configures client and server from the same source, so the
 * relationship the test cares about survives. Scaling one end of a connection whose peer is a fixture,
 * a foreign stack, or a recorded trace changes what is under test rather than how long it is given.
 *
 * Do **not** apply it to a value an assertion reads back as data — a measured elapsed time, or a
 * constant the assertion itself recomputes. The point of the uniform factor is that it moves
 * deadlines without moving the facts they are waiting on.
 */
val Duration.scaled: Duration get() = this * testTimeScale()
