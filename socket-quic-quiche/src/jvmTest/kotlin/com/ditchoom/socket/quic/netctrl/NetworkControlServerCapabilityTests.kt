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
            return if (rootAvailable) ShellOutcome.Ran("") else ShellOutcome.Failed(127, SU_MISSING)
        }
    }

    private companion object {
        const val SU_MISSING = "su: inaccessible or not found"
    }
}
