package com.ditchoom.socket.testkit.skip

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The marker format is a contract: CI greps it out of the test XML to inventory what a lane did not
 * run. A silent change to the shape would make the inventory come back empty, which reads exactly
 * like "nothing was skipped" — the failure mode this whole API exists to remove.
 */
class TestSkipTests {
    private val everyReason =
        listOf(
            SkipReason.NativeLibraryUnavailable("libquiche.so"),
            SkipReason.SimulatorLacksFixtures("no testcerts/ under simctl --standalone"),
            SkipReason.SimulatorLacksNetworkServices("simctl --standalone runs outside launchd_sim"),
            SkipReason.TransportUnavailable("TCP in a browser"),
            SkipReason.HostBehaviourDiffers("Windows Iocp connect-refused codes"),
            SkipReason.OptInLaneNotRequested("SIM_FUZZ_ITERATIONS unset"),
        )

    @Test
    fun markerCarriesSiteReasonAndDetail() {
        val marker = skipMarker("AppleHttp3LoopbackTest", SkipReason.SimulatorLacksFixtures("no testcerts/ cwd"))

        assertEquals(
            "[TEST-SKIPPED] site=AppleHttp3LoopbackTest reason=simulator-lacks-fixtures detail=no testcerts/ cwd",
            marker,
        )
    }

    @Test
    fun everyReasonHasADistinctNonBlankLabel() {
        val labels = everyReason.map { it.label }

        assertEquals(labels.size, labels.toSet().size, "SkipReason labels must be distinct: $labels")
        assertTrue(labels.none { it.isBlank() }, "SkipReason labels must be non-blank: $labels")
    }

    @Test
    fun everyReasonIsGreppableByTheSharedPrefix() {
        for (reason in everyReason) {
            assertTrue(
                skipMarker("AnySuite", reason).startsWith(SKIP_MARKER),
                "marker for ${reason.label} must start with $SKIP_MARKER",
            )
        }
    }

    @Test
    fun theSiteInAMarkerIsTheClassItWasGiven() {
        // The site is a KClass parameter rather than an `Any` receiver because an extension on `Any`
        // binds to the innermost implicit receiver: every call inside a `runTest`/`runBlocking` body
        // recorded `site=TestScopeImpl` / `site=BlockingCoroutine` instead of the suite. The inventory
        // groups by site, so that column named a coroutine class on exactly the lanes it exists for.
        assertContains(
            skipMarker(TestSkipTests::class.simpleName ?: "", SkipReason.TransportUnavailable("probe")),
            "site=TestSkipTests",
        )
    }

    @Test
    fun readingAnUnsetVariableIsNullOnEveryPlatform() {
        // The only test that executes `testkitEnv` at all. Its js/wasmJs actuals reach for `process.env`
        // through a `js(...)` block that no test had ever run — they compiled, which is not the same as
        // working, and a throw there would have turned every skip on those lanes into an error.
        assertNull(testkitEnv("SOCKET_TESTKIT_VARIABLE_THAT_IS_NEVER_SET"))
    }

    @Test
    fun theForbiddenSkipFailureNamesTheMarkerAndTheEscapeHatch() {
        val marker = skipMarker("QuicLargePayloadTests", SkipReason.NativeLibraryUnavailable("libquiche"))

        val message = skipForbidden(marker).message ?: ""

        // Both halves matter: the marker says what was skipped, the env var name says how to
        // change the decision. A failure carrying only one of them sends the reader hunting.
        assertContains(message, marker)
        assertContains(message, REQUIRE_ALL_TESTS_ENV)
    }
}
