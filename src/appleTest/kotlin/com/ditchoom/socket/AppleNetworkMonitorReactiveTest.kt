package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reactive re-emit coverage for [AppleNetworkMonitor] — the macOS analog of the Linux netns
 * `NetnsReactiveChangeTest`: proves the live `NWPathMonitor` callback re-fires and the
 * [availability]/[networkId] StateFlows RE-EMIT when the machine's primary path actually changes, not
 * just the one-shot first update ([AppleNetworkMonitorLiveTests] covers that).
 *
 * There is no network namespace on macOS, so the path change is driven against the real machine and
 * cannot be made hermetic. This test is therefore **env-gated and CI-skipped** (GitHub-hosted macOS
 * runners have no guaranteed Wi-Fi device and no safe way to drop the primary link) — the same honest
 * end-state as the Linux Wi-Fi `mac80211_hwsim` step: harness committed and runnable wherever the
 * environment allows, never silently green-passing as if it ran.
 *
 * Run it locally with:
 * ```
 * NETMON_REACT=1 ./gradlew :macosArm64Test --tests '*AppleNetworkMonitorReactiveTest*' -i
 * ```
 * then toggle your primary link (Wi-Fi off, wait, on) within the timeout.
 *
 * The change is driven **externally**, which keeps the seam both portable and scriptable: this test
 * only awaits the re-emit, so whatever causes the change is orthogonal — a human toggling Wi-Fi, or an
 * automation running a shell command *in parallel* (e.g.
 * `networksetup -setairportpower en0 off && sleep 3 && networksetup -setairportpower en0 on`). The test
 * deliberately does not exec a driver itself: shell exec is unavailable on iOS/tvOS/watchOS (no
 * `platform.posix.system`), and keeping the driver out of process lets the same `appleTest` compile and
 * run on every Apple target (manual toggle on a real device) with no platform-specific code.
 */
class AppleNetworkMonitorReactiveTest {
    @Test
    fun networkIdReEmitsWhenThePrimaryPathChanges() {
        val gate = NSProcessInfo.processInfo.environment["NETMON_REACT"] as? String
        if (gate.isNullOrEmpty()) {
            // Not under the reactive harness — no-op. This test asserts nothing in CI by design; it is
            // never advertised as reactive coverage there (see the class doc / PR notes).
            println("SKIP AppleNetworkMonitorReactiveTest: set NETMON_REACT=1 and toggle the primary link to run it.")
            return
        }

        runTestNoTimeSkipping(timeout = 180.seconds) {
            val monitor = AppleNetworkMonitor()
            try {
                // Establish the baseline: wait for the first resolved, usable path so there is a concrete
                // (availability, networkId) to observe a change *away from*.
                val initial =
                    combine(monitor.availability, monitor.networkId) { a, id -> a to id }
                        .first { (a, id) -> a == NetworkAvailability.AVAILABLE && id is NetworkId.Link }
                println("baseline path: availability=${initial.first} networkId=${initial.second}")

                println("========================================================================")
                println(" DRIVE A PRIMARY-LINK CHANGE NOW — toggle Wi-Fi OFF, wait ~3s, then ON")
                println(" (manually, or via an external script running in parallel).")
                println(" Waiting up to ~2.5 min for the path to change and NWPathMonitor to react…")
                println("========================================================================")

                // The callback must re-fire and the flows must re-emit a value distinct from the baseline
                // (a different availability, or a different link — new index/kind, or Unidentified while
                // the link is down). A StateFlow keeps no history, so an external driver that flips and
                // restores the link must overlap this await — start the toggle after the baseline print.
                // A timeout here means the monitor did NOT react to a real change.
                val changed =
                    combine(monitor.availability, monitor.networkId) { a, id -> a to id }
                        .first { it != initial }
                println("REACTED: path changed to availability=${changed.first} networkId=${changed.second}")
            } finally {
                monitor.close()
            }
        }
    }
}
