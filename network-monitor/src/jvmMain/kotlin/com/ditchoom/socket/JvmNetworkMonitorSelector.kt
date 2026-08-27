@file:JvmName("JvmNetworkMonitorSelectorKt")

package com.ditchoom.socket

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base (JDK 8–20) network-monitor selector for the JVM.
 *
 * This is the *shadowed* half of a multi-release JAR: the `jvm21Main` source set
 * ships a same-named class under `META-INF/versions/21` that the JVM loads instead
 * on JDK 21+, returning a reactive FFM routing-socket monitor. On older JDKs there
 * is no event-driven network-change API, so we fall back to interface polling.
 *
 * Public because the owning platform module (`com.ditchoom:socket`) delegates its
 * `NetworkMonitor.default()` JVM actual here across the module boundary.
 */
fun defaultJvmNetworkMonitor(): NetworkMonitor {
    warnIfReactiveTierWasStripped()
    return PollingNetworkMonitor()
}

/**
 * The two tiers of this jar are not perf variants of one behaviour — [PollingNetworkMonitor] samples
 * on an interval (5s by default), the `versions/21` tier is event-driven — so losing the versioned
 * tier changes what the library *does*, not how fast it does it. It is visible in
 * [NetworkMonitor.capability] as [MonitorMechanism.Polled], but only to a consumer who thought to
 * look, which is precisely what a silent degrade prevents.
 *
 * And it is losable. ProGuard discards everything under `META-INF/versions/` outright: it does not
 * read those entries as classes and does not copy them as resources. The manifest still advertises
 * `Multi-Release: true` afterwards, so nothing downstream notices. A `-keep` rule cannot repair it
 * either — a keep cannot preserve a class the shrinker never saw.
 *
 * **This function is the base tier noticing that it should not have been chosen.** Reaching here on
 * a JDK that ships [REACTIVE_TIER_JDK]'s API means the versioned selector was supposed to shadow
 * this one and could not, which is only possible if it is no longer in the artifact. That check
 * lives in the tier that always survives shrinking, so it cannot itself be stripped — the degrade
 * stays, but it stops being silent, which is the half that costs consumers debugging time.
 *
 * It warns only when the reactive classes are *absent*. They can also be present and simply lose to
 * classpath order — running against exploded class directories rather than a jar, as this repo's own
 * `jvmTest` does when it appends the `java21` output — and that is a local classpath arrangement
 * rather than a damaged artifact, so it is left alone.
 */
private fun warnIfReactiveTierWasStripped() {
    if (!warnedAboutStrippedTier.compareAndSet(false, true)) return
    val present = classPresent(REACTIVE_TIER_CLASS)
    if (!reactiveTierWasStripped(System.getProperty("java.specification.version"), present)) return
    System.err.println(STRIPPED_TIER_WARNING)
    System.err.flush()
}

private val warnedAboutStrippedTier = AtomicBoolean(false)

/**
 * Whether [name] is in the artifact. Loaded, never initialized — this is a question about
 * packaging, and running a class initializer to answer it would be a side effect on a path taken
 * during ordinary startup.
 */
internal fun classPresent(name: String): Boolean =
    try {
        Class.forName(name, false, NetworkMonitor::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        // Found, but its own dependencies did not resolve. Present-but-broken is a different
        // complaint than stripped, and not one this warning would help with.
        true
    }

/**
 * Whether the base tier is running where the `versions/21` tier should have shadowed it.
 *
 * Split from its call site so it can be tested on both answers without a classloader that lies:
 * the interesting case is a JDK 21+ runtime whose artifact has no reactive classes, which no test
 * JVM running this suite can be in.
 */
internal fun reactiveTierWasStripped(
    specVersion: String?,
    reactiveTierPresent: Boolean,
): Boolean = !reactiveTierPresent && jdkIsAtLeast(specVersion, REACTIVE_TIER_JDK)

/**
 * `java.specification.version` is `"1.8"` through JDK 8 and `"9"`, `"21"`, occasionally `"21.0.2"`
 * after it. Anything unparseable answers `false`: an unrecognised runtime is not evidence of a
 * damaged artifact, and a warning that fires on a guess is worse than none.
 */
internal fun jdkIsAtLeast(
    specVersion: String?,
    feature: Int,
): Boolean {
    val raw = specVersion?.trim().orEmpty()
    val body = if (raw.startsWith("1.")) raw.substring(2) else raw
    val parsed = body.takeWhile { it.isDigit() }.toIntOrNull() ?: return false
    return parsed >= feature
}

/** Ships only in `META-INF/versions/21`, so its absence on a JDK 21+ runtime is the whole signal. */
internal const val REACTIVE_TIER_CLASS = "com.ditchoom.socket.FfmRoutingSocketNetworkMonitor"

/** The tier directory, and the JDK that selects it. */
internal const val REACTIVE_TIER_JDK = 21

private const val BANNER = "===== com.ditchoom:network-monitor — REACTIVE TIER MISSING ====="

internal val STRIPPED_TIER_WARNING =
    """
    |$BANNER
    |This JDK is $REACTIVE_TIER_JDK or newer, but META-INF/versions/$REACTIVE_TIER_JDK is not in the
    |artifact, so NetworkMonitor.default() has fallen back to a polling monitor instead of the
    |event-driven routing-socket one (rtnetlink on Linux, PF_ROUTE on macOS).
    |
    |Effect: network changes are seen late, and a change that resolves inside the poll window is
    |never seen at all. Anything driven by network transitions degrades with it — most visibly
    |QuicOptions.autoMigrateOnNetworkChange, which cannot migrate off a link it never hears die.
    |
    |Cause: a build step dropped the multi-release tier. ProGuard does this by default — it neither
    |reads META-INF/versions/** as classes nor copies it as resources, and leaves Multi-Release: true
    |in the manifest afterwards. A -keep rule cannot bring it back: a keep cannot preserve a class
    |the shrinker never saw.
    |
    |Fix: flatten multi-release jars onto their root before the shrinker runs, or exclude this
    |artifact from shrinking. Recipe:
    |https://ditchoom.github.io/socket/docs/guides/shrinking-and-multi-release-jars
    |$BANNER
    """.trimMargin()
