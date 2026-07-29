package com.ditchoom.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.socket.testkit.NetworkMonitorRecorder
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures a **real** Android network flap as a replayable [TraceEvent] trace — the record half of the
 * record/replay loop (RFC_UNIFIED_NETWORK_TEST_HARNESS §7), pointed at a physical device.
 *
 * This is not a regression test; it is the tool that *produces* one. It drives genuine radio
 * transitions through `UiAutomation.executeShellCommand` (which runs with shell privileges, so no root
 * is needed), records what [AndroidNetworkMonitor] publishes while they happen via the shipped
 * [NetworkMonitorRecorder], and emits the result as `v1` trace lines on logcat. The host extracts them
 * into the fixture in `AndroidDeviceFlapReplayTests`, which replays the same flap through a
 * `ScriptedNetworkMonitor` on **every** platform with no device attached.
 *
 * Why a device and not the emulator: the emulator's NAT validates instantly, so the window where a
 * network has `NET_CAPABILITY_INTERNET` but not yet `NET_CAPABILITY_VALIDATED` never opens there. On
 * real Wi-Fi it does — measured at ~757ms on the capture device — and that window is exactly what
 * [AndroidNetworkMonitor] reports `AVAILABLE` through.
 *
 * **Opt-in.** Toggling Wi-Fi and airplane mode would wreck any suite sharing the device, so this is
 * gated behind an explicit runner argument and reports its skip rather than passing silently:
 * ```
 * ./gradlew :connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.ditchoom.socket.AndroidNetworkMonitorTraceCapture \
 *   -Pandroid.testInstrumentationRunnerArguments.captureNetworkTrace=true
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorTraceCapture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }.trim()
    }

    private fun enabled(): Boolean = InstrumentationRegistry.getArguments().getString("captureNetworkTrace")?.toBoolean() == true

    @Test
    fun captureWifiAndAirplaneFlap() {
        if (!enabled()) {
            // Loud, not silent: a quiet skip here reads identically to a successful capture.
            Log.w(TAG, "SKIP: captureNetworkTrace not set — this lane only runs when explicitly requested")
            println("network-trace capture SKIP: captureNetworkTrace runner argument not set")
        }
        assumeTrue(enabled())

        val events = CopyOnWriteArrayList<TraceEvent>()
        val sink = TraceSink { events += it }
        val recorder = NetworkMonitorRecorder(sink)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Diagnostics only — the raw framework callbacks alongside the monitor's published state, so a
        // mismatch between what Android delivered and what we published is visible in logcat.
        val rawWatcher = rawCallbackWatcher()
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            rawWatcher,
        )

        val monitor = NetworkMonitor.android(context)
        val recording = recorder.observe(monitor, scope)
        try {
            Thread.sleep(BASELINE_MS)

            Log.i(TAG, "phase 1: wifi down/up (break-before-make + validation lag)")
            shell("cmd -w wifi set-wifi-enabled disabled")
            Thread.sleep(WIFI_DOWN_MS)
            shell("cmd -w wifi set-wifi-enabled enabled")
            Thread.sleep(WIFI_UP_MS)

            Log.i(TAG, "phase 2: airplane mode on/off (whole-radio teardown)")
            shell("cmd connectivity airplane-mode enable")
            Thread.sleep(AIRPLANE_ON_MS)
            shell("cmd connectivity airplane-mode disable")
            Thread.sleep(AIRPLANE_OFF_MS)
        } finally {
            shell("cmd connectivity airplane-mode disable")
            shell("cmd -w wifi set-wifi-enabled enabled")
            recording.cancel()
            scope.cancel()
            runBlocking { }
            cm.unregisterNetworkCallback(rawWatcher)
            monitor.close()
        }

        val lines = events.map { it.toString() }

        // logcat is the transport, not a file: scoped storage on API 30+ makes the test app's external
        // files dir unreadable to `adb pull`, and internal storage needs run-as. One marker per line
        // extracts with a single sed and cannot be partially written the way a pulled file can.
        //
        //   adb logcat -d -s NetMonCapture | sed -n 's/^.*NetMonCapture: TRACE_LINE //p'
        //
        println("network-trace capture OK: ${lines.size} events")
        Log.i(TAG, "TRACE_EVENTS=${lines.size}")
        lines.forEach { Log.i(TAG, "TRACE_LINE $it") }
    }

    /**
     * Investigation lane: what does the monitor publish on a network that is connected and claims
     * `NET_CAPABILITY_INTERNET` but never earns `NET_CAPABILITY_VALIDATED`?
     *
     * Android decides validation by fetching its connectivity-probe URL and requiring a 204. Pointing
     * that URL at a host which answers 200-with-a-body is exactly what a captive portal looks like from
     * the framework's side, so this reproduces a portal on ordinary Wi-Fi with no portal hardware. The
     * probe URLs are restored here and again host-side.
     *
     * Gated separately from the flap capture because it rewrites global settings:
     * `-Pandroid.testInstrumentationRunnerArguments.captureCaptivePortal=true`
     */
    @Test
    fun captureCaptivePortalStyleNetwork() {
        val on = InstrumentationRegistry.getArguments().getString("captureCaptivePortal")?.toBoolean() == true
        if (!on) {
            Log.w(TAG, "SKIP: captureCaptivePortal not set")
            println("captive-portal capture SKIP: captureCaptivePortal runner argument not set")
        }
        assumeTrue(on)

        val events = CopyOnWriteArrayList<TraceEvent>()
        val recorder = NetworkMonitorRecorder(TraceSink { events += it })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val rawWatcher = rawCallbackWatcher()
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            rawWatcher,
        )
        val monitor = NetworkMonitor.android(context)
        val recording = recorder.observe(monitor, scope)
        try {
            Log.i(TAG, "pointing connectivity probes at a 200-with-body responder")
            shell("settings put global captive_portal_http_url http://example.com/index.html")
            shell("settings put global captive_portal_https_url https://example.com/index.html")
            shell("settings put global captive_portal_use_https 0")

            // Validation only runs on (re)association, so the network has to be bounced.
            shell("cmd -w wifi set-wifi-enabled disabled")
            Thread.sleep(WIFI_DOWN_MS)
            shell("cmd -w wifi set-wifi-enabled enabled")
            Thread.sleep(PORTAL_SETTLE_MS)

            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            Log.i(TAG, "PORTAL activeNetwork=$active")
            Log.i(TAG, "PORTAL hasINTERNET=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
            Log.i(TAG, "PORTAL hasVALIDATED=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            Log.i(TAG, "PORTAL hasCAPTIVE_PORTAL=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}")
            Log.i(TAG, "PORTAL monitor.availability=${monitor.availability.value}")
            Log.i(TAG, "PORTAL monitor.networkId=${monitor.networkId.value}")
        } finally {
            shell("settings delete global captive_portal_http_url")
            shell("settings delete global captive_portal_https_url")
            shell("settings delete global captive_portal_use_https")
            shell("cmd -w wifi set-wifi-enabled enabled")
            recording.cancel()
            scope.cancel()
            cm.unregisterNetworkCallback(rawWatcher)
            monitor.close()
        }
        events.map { it.toString() }.forEach { Log.i(TAG, "TRACE_LINE $it") }
    }

    private fun rawCallbackWatcher() =
        object : ConnectivityManager.NetworkCallback() {
            private fun caps(c: NetworkCapabilities): String =
                buildList {
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("INTERNET")
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("VALIDATED")
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("CAPTIVE_PORTAL")
                }.toString()

            override fun onAvailable(network: Network) {
                Log.i(TAG, "RAW onAvailable(h=${network.networkHandle})")
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "RAW onLost(h=${network.networkHandle})")
            }

            override fun onUnavailable() {
                Log.i(TAG, "RAW onUnavailable()")
            }

            override fun onLosing(
                network: Network,
                maxMsToLive: Int,
            ) {
                Log.i(TAG, "RAW onLosing(h=${network.networkHandle}, ttl=$maxMsToLive)")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                c: NetworkCapabilities,
            ) {
                Log.i(TAG, "RAW onCapabilitiesChanged(h=${network.networkHandle}, ${caps(c)})")
            }
        }

    private companion object {
        const val TAG = "NetMonCapture"
        const val BASELINE_MS = 2_000L
        const val WIFI_DOWN_MS = 8_000L
        const val WIFI_UP_MS = 25_000L
        const val AIRPLANE_ON_MS = 8_000L
        const val AIRPLANE_OFF_MS = 25_000L
        const val PORTAL_SETTLE_MS = 30_000L
    }
}
