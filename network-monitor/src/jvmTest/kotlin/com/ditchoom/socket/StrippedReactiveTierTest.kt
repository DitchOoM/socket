package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The base tier has to be able to tell that it should not have been chosen.
 *
 * `META-INF/versions/21` is behaviour, not performance — polling versus event-driven — and ProGuard
 * discards versioned entries outright while leaving `Multi-Release: true` in the manifest, so a
 * shrunk consumer runs different code than every unshrunk build of the same commit and nothing says
 * so. The base tier is the half that always survives a shrinker, which makes it the only place that
 * can notice.
 *
 * The signal is tested rather than the print: the interesting runtime — JDK 21+ with no reactive
 * classes in the artifact — is one no JVM running this suite can be in, so the decision is a pure
 * function here and only its inputs are read at the call site.
 */
class StrippedReactiveTierTest {
    @Test
    fun aJdk21RuntimeWithNoReactiveClassesIsAStrippedArtifact() {
        assertTrue(reactiveTierWasStripped(specVersion = "21", reactiveTierPresent = false))
        assertTrue(reactiveTierWasStripped(specVersion = "21.0.2", reactiveTierPresent = false))
        assertTrue(reactiveTierWasStripped(specVersion = "25", reactiveTierPresent = false))
    }

    @Test
    fun anIntactArtifactIsSilentEvenWhenTheBaseTierRuns() {
        // Exploded class directories let the base tier win on classpath order with the reactive
        // classes still right there — a local arrangement, not a damaged artifact.
        assertFalse(reactiveTierWasStripped(specVersion = "21", reactiveTierPresent = true))
    }

    @Test
    fun anOlderJdkIsTheBaseTierWorkingAsDesigned() {
        // Nothing to strip: the tier was never selected on these, so polling is the correct answer.
        assertFalse(reactiveTierWasStripped(specVersion = "1.8", reactiveTierPresent = false))
        assertFalse(reactiveTierWasStripped(specVersion = "11", reactiveTierPresent = false))
        assertFalse(reactiveTierWasStripped(specVersion = "17", reactiveTierPresent = false))
    }

    @Test
    fun anUnreadableRuntimeVersionNeverAccusesTheBuild() {
        // A warning that fires on a guess is worse than none.
        for (unknown in listOf(null, "", "   ", "unknown", "sapmachine")) {
            assertFalse(
                reactiveTierWasStripped(specVersion = unknown, reactiveTierPresent = false),
                "spec version <$unknown> is not evidence of a stripped tier",
            )
        }
    }

    @Test
    fun theVersionParseFollowsBothJdkNamingSchemes() {
        assertFalse(jdkIsAtLeast("1.8", 21), "1.8 is JDK 8, not 18")
        assertTrue(jdkIsAtLeast("1.8", 8))
        assertTrue(jdkIsAtLeast("9", 9))
        assertTrue(jdkIsAtLeast("21", 21))
        assertFalse(jdkIsAtLeast("20", 21))
        assertTrue(jdkIsAtLeast("21.0.9", 21))
    }

    @Test
    fun theProbeAnswersOnPresenceAndNotOnAnythingElse() {
        // Both branches of the one runtime-dependent step, against real classes: the decision above
        // is only worth testing if the input it reads is actually derived from the artifact.
        assertTrue(classPresent("com.ditchoom.socket.PollingNetworkMonitor"), "base tier is always present")
        assertFalse(classPresent("com.ditchoom.socket.NoSuchMonitorWasEverShipped"))
    }

    @Test
    fun theWarningNamesTheCauseAndTheFix() {
        // A loud degrade that does not say what to do about it is just noise.
        assertTrue(STRIPPED_TIER_WARNING.contains("META-INF/versions/"))
        assertTrue(STRIPPED_TIER_WARNING.contains("-keep"), "must pre-empt the rule that cannot work")
        assertTrue(STRIPPED_TIER_WARNING.contains("shrinking-and-multi-release-jars"), "must link the recipe")
    }

    @Test
    fun theClassThisDetectionWatchesForOnlyEverShipsInTheVersionedTier() {
        // If this class is ever moved to the jar root the detection silently stops working, because
        // it would then be present in exactly the artifact it is supposed to accuse.
        val versionedOnly =
            javaClass.classLoader
                .getResource(REACTIVE_TIER_CLASS.replace('.', '/') + ".class")
        assertTrue(
            versionedOnly == null || versionedOnly.toString().contains("java21"),
            "$REACTIVE_TIER_CLASS must ship only under META-INF/versions/$REACTIVE_TIER_JDK, " +
                "but it resolved to $versionedOnly",
        )
    }
}
