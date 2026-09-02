package com.ditchoom.socket.quic.netctrl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The control server answers for what it actually did (#389).
 *
 * Every impairment runs as `adb shell su 0 <iptables/tc/settings>`. On a device without root each of
 * those returns `su: inaccessible or not found`; the server logged that as **non-fatal** and still
 * replied `NetCtrlResponse.Ok`, so the device believed the impairment had been applied and the test
 * proceeded against a completely healthy network. Measured on a physical Samsung SM-F956U1: all five
 * `AndroidQuicMigrationTests` cases **passed** with no UDP blocked, no latency added, and airplane
 * mode never toggled.
 *
 * That is the defect class the typed-skip work exists to remove — a test reporting success without
 * exercising its subject — and it is worse than a silent skip, because a skip is at least countable
 * while a vacuous pass is indistinguishable from a real one in every report we produce.
 *
 * **Why these tests need a seam.** Every state that matters here is one `adb` cannot be asked to
 * produce on demand: a device without root, a wrong serial, `adb` missing from the PATH. A decision
 * no test can drive is how this lived under a green suite for months, so [DeviceShell] is a
 * parameter and these drive it directly. Nothing here starts a process or touches a device.
 *
 * On a rooted emulator the impairments genuinely applied, so CI was never vacuous — this bit only on
 * physical hardware, which is exactly where the interesting handoff behaviour lives.
 */
class NetworkControlServerCapabilityTests {
    /**
     * The regression gate. An impairment whose device command failed must not answer `Ok`.
     *
     * RED against the previous server for every one of these commands: it called `adb(...)`, ignored
     * the result, and returned `Ok` unconditionally.
     */
    @Test
    fun anImpairmentThatFailedOnTheDeviceIsAnError() {
        val shell = ScriptedShell(rootAvailable = false)
        val server = serverWith(shell)

        val impairments =
            listOf(
                NetCtrlCommand.BlockUdp(),
                NetCtrlCommand.UnblockUdp(),
                NetCtrlCommand.AddLatency(200),
                NetCtrlCommand.RemoveLatency(),
                NetCtrlCommand.AirplaneOn(),
            )
        for (command in impairments) {
            val response = server.dispatchForTest(command)
            val error =
                assertIs<NetCtrlResponse.Error>(
                    response,
                    "$command answered $response on a device where `su 0` does not exist. That is #389: " +
                        "the device believes the network was impaired and the test runs against a healthy one",
                )
            assertTrue(
                SU_MISSING in error.message,
                "the error must carry what the device said, not just that something failed: ${error.message}",
            )
        }
    }

    /**
     * The positive control, through the same seam: with the device serving the commands, the same
     * impairments answer `Ok`. Without this the suite would only prove that failures fail.
     */
    @Test
    fun anImpairmentThatAppliedIsOk() {
        val shell = ScriptedShell(rootAvailable = true)
        val server = serverWith(shell)

        assertIs<NetCtrlResponse.Ok>(server.dispatchForTest(NetCtrlCommand.BlockUdp()))
        assertIs<NetCtrlResponse.Ok>(server.dispatchForTest(NetCtrlCommand.AddLatency(120)))
        assertTrue(
            shell.ran.any { it.startsWith("iptables -A") } && shell.ran.any { it.startsWith("tc qdisc add") },
            "the impairments must actually reach the device: ${shell.ran}",
        )
    }

    /**
     * The capability is a question the host answers, not one the caller assumes.
     *
     * `Ping` deliberately still answers `Ok` on the same unrooted device — it reports that the server
     * is up, which is true and is a different question. Conflating the two is what made "the control
     * server answered" read as "the network can be impaired".
     */
    @Test
    fun anUnrootedDeviceReportsImpairmentUnavailableWhileStillAnsweringPing() {
        val server = serverWith(ScriptedShell(rootAvailable = false))

        val capability = assertIs<NetCtrlResponse.ImpairmentUnavailable>(server.dispatchForTest(NetCtrlCommand.QueryImpairment()))
        assertTrue(SU_MISSING in capability.why, "the reason must be the device's own words: ${capability.why}")
        assertIs<NetCtrlResponse.Ok>(
            server.dispatchForTest(NetCtrlCommand.Ping()),
            "Ping answers `is the server up`, which is still yes — the whole point is that it is a different question",
        )
    }

    @Test
    fun aRootedDeviceReportsImpairmentAvailable() {
        val server = serverWith(ScriptedShell(rootAvailable = true))
        assertIs<NetCtrlResponse.ImpairmentAvailable>(server.dispatchForTest(NetCtrlCommand.QueryImpairment()))
    }

    /**
     * The capability is decided once. `su 0 id` is one adb round trip and every impairment would
     * otherwise re-ask it; more importantly, a capability that could change answers mid-run would let
     * one test be impaired and the next not, with nothing saying so.
     */
    @Test
    fun theCapabilityIsDecidedOnce() {
        val shell = ScriptedShell(rootAvailable = true)
        val server = serverWith(shell)
        repeat(3) { server.dispatchForTest(NetCtrlCommand.QueryImpairment()) }
        assertEquals(1, shell.ran.count { it == "id" }, "`su 0 id` should be asked once, not per query: ${shell.ran}")
    }

    /**
     * A failed *removal* must not forget its cleanup.
     *
     * `UnblockUdp` and `RemoveLatency` untrack the undo they just ran. Untracking one whose device
     * command failed would drop the cleanup for a rule that is still installed, leaving it behind for
     * every later test on that emulator — the failure mode that outlives the run it happened in.
     */
    @Test
    fun aFailedRemovalKeepsItsCleanupTracked() {
        val shell = ScriptedShell(rootAvailable = true)
        val server = serverWith(shell)
        assertIs<NetCtrlResponse.Ok>(server.dispatchForTest(NetCtrlCommand.BlockUdp()))

        shell.rootAvailable = false
        assertIs<NetCtrlResponse.Error>(server.dispatchForTest(NetCtrlCommand.UnblockUdp()))

        assertEquals(
            listOf("iptables -D OUTPUT -p udp -j DROP"),
            server.trackedForTest(),
            "the undo for a rule that is still installed must survive a failed removal",
        )
    }

    /**
     * The #554 gate. Latency must be applied to the interface the device actually routes through.
     *
     * `eth0` was hardcoded and no emulator image has one — API 29 routes through `radio0` — so
     * `tc qdisc add dev eth0` failed with `Cannot find device "eth0"`. RED against the previous
     * server, which named `eth0` unconditionally.
     *
     * This is the second half of #389. That change made the server answer for what it did, which
     * turned this from a silent no-op into a red test; naming the right interface is what makes the
     * impairment actually happen. The two together are the difference between a migration test that
     * passes having impaired nothing and one that means something.
     */
    @Test
    fun latencyIsAppliedToTheInterfaceTheDeviceActuallyRoutesThrough() {
        val shell = ScriptedShell(rootAvailable = true)
        val server = serverWith(shell)

        assertIs<NetCtrlResponse.Ok>(server.dispatchForTest(NetCtrlCommand.AddLatency(500)))

        assertTrue(
            shell.ran.any { it == "tc qdisc add dev radio0 root netem delay 500ms" },
            "the impairment must name the routed interface, not a guess: ${shell.ran}",
        )
        assertTrue(
            shell.ran.none { "eth0" in it },
            "no command may name eth0 — that is the device that does not exist (#554): ${shell.ran}",
        )
        assertEquals(
            listOf("tc qdisc del dev radio0 root"),
            server.trackedForTest(),
            "the tracked undo must name the same interface the impairment used, or cleanup leaves the qdisc installed",
        )
    }

    /**
     * A device whose routing table names no interface answers an error, rather than impairing nothing.
     *
     * The failure this replaces is the #389 shape again: with no interface discoverable there is
     * nothing to apply `tc` to, and the only honest answer is to say so. Silently falling back to a
     * constant would put us back where we started.
     */
    @Test
    fun aDeviceWithNoDiscoverableRouteAnswersErrorRatherThanImpairingNothing() {
        val shell = RouteLessShell()
        val server = serverWith(shell)

        val error = assertIs<NetCtrlResponse.Error>(server.dispatchForTest(NetCtrlCommand.AddLatency(200)))
        assertTrue(
            "Network is unreachable" in error.message,
            "the error must carry the device's own words: ${error.message}",
        )
        assertTrue(
            shell.ran.none { it.startsWith("tc ") },
            "no tc command may be attempted when there is no interface to apply it to: ${shell.ran}",
        )
        assertTrue(server.trackedForTest().isEmpty(), "nothing applied, so nothing to undo")
    }

    /**
     * The interface is discovered once, for the reason the capability is: an impairment and its
     * removal that disagreed about the interface would leave the qdisc installed.
     */
    @Test
    fun theEgressInterfaceIsDiscoveredOnce() {
        val shell = ScriptedShell(rootAvailable = true)
        val server = serverWith(shell)

        server.dispatchForTest(NetCtrlCommand.AddLatency(100))
        server.dispatchForTest(NetCtrlCommand.RemoveLatency())

        assertEquals(
            1,
            shell.ran.count { it == NetworkControlServer.EGRESS_ROUTE_QUERY },
            "the route should be asked once, not per impairment: ${shell.ran}",
        )
    }

    /** The parser keys on the `dev` marker, because field order differs across images. */
    @Test
    fun theRouteParserReadsTheInterfaceRegardlessOfFieldOrder() {
        assertEquals(
            "radio0",
            NetworkControlServer.parseEgressInterface("8.8.8.8 via 10.0.2.2 dev radio0 src 10.0.2.16 uid 0"),
        )
        assertEquals(
            "wlan0",
            NetworkControlServer.parseEgressInterface("8.8.8.8 via 192.168.1.1 dev wlan0 table 1021 src 192.168.1.5"),
        )
        assertEquals(
            "radio0",
            NetworkControlServer.parseEgressInterface("   8.8.8.8   via  10.0.2.2   dev   radio0  \n"),
        )
        assertEquals(
            null,
            NetworkControlServer.parseEgressInterface("RTNETLINK answers: Network is unreachable"),
            "output naming no interface must not be read as one",
        )
        assertEquals(null, NetworkControlServer.parseEgressInterface(""), "empty output names no interface")
        assertEquals(
            null,
            NetworkControlServer.parseEgressInterface("8.8.8.8 via 10.0.2.2 dev"),
            "a trailing `dev` with nothing after it is not an interface name",
        )
    }

    private fun serverWith(shell: DeviceShell) = NetworkControlServer(port = 0, adbSerial = null, shell = shell)

    /**
     * A device that either serves privileged commands or has no `su`, and records what it was asked.
     *
     * [rootAvailable] is a `var` so one test can change the answer mid-run — the shape
     * [aFailedRemovalKeepsItsCleanupTracked] needs and a real device cannot be asked for.
     */
    private class ScriptedShell(
        var rootAvailable: Boolean,
    ) : DeviceShell {
        val ran = mutableListOf<String>()

        override fun run(shellCommand: String): ShellOutcome {
            ran += shellCommand
            // 127 and this message are what an unrooted device actually produces; the assertions read
            // the text, so a fake that invented its own would prove nothing about the real one.
            if (!rootAvailable) return ShellOutcome.Failed(127, SU_MISSING)
            // Verbatim from an API-29 emulator. The interface is `radio0`, not `eth0` — the whole of
            // #554 — so a fake that answered a made-up name would not hold the fix honest.
            if (shellCommand == NetworkControlServer.EGRESS_ROUTE_QUERY) return ShellOutcome.Ran(ROUTE_GET_API29)
            return ShellOutcome.Ran("")
        }
    }

    /** A device that serves privileged commands but whose routing table names no interface. */
    private class RouteLessShell : DeviceShell {
        val ran = mutableListOf<String>()

        override fun run(shellCommand: String): ShellOutcome {
            ran += shellCommand
            if (shellCommand == NetworkControlServer.EGRESS_ROUTE_QUERY) {
                return ShellOutcome.Failed(2, "RTNETLINK answers: Network is unreachable")
            }
            return ShellOutcome.Ran("")
        }
    }

    private companion object {
        const val SU_MISSING = "su: inaccessible or not found"
        const val ROUTE_GET_API29 = "8.8.8.8 via 10.0.2.2 dev radio0 src 10.0.2.16 uid 0"
    }
}
