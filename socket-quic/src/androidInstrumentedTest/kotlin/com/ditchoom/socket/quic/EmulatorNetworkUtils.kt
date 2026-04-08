package com.ditchoom.socket.quic

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utilities for manipulating network state on a rooted Android emulator.
 *
 * All commands require root access (`su`). Call [isRooted] first to verify.
 */
object EmulatorNetworkUtils {
    /**
     * Detected `su` invocation style. Newer emulators (API 30+) use `su 0 <cmd>`
     * while older ones use `su -c <cmd>`. Detected once and cached.
     */
    private val suStyle: SuStyle by lazy { detectSuStyle() }

    private enum class SuStyle { DASH_C, NUMERIC, NONE }

    /** Known locations for the `su` binary on various emulator images. */
    private val suPaths = listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")

    private fun detectSuStyle(): SuStyle {
        for (su in suPaths) {
            // Try "su 0 id" first (works on newer emulators / userdebug builds)
            try {
                val p = Runtime.getRuntime().exec(arrayOf(su, "0", "id"))
                if (p.waitFor() == 0) {
                    suBinary = su
                    return SuStyle.NUMERIC
                }
            } catch (_: Exception) {}
            // Fall back to "su -c id" (older emulators)
            try {
                val p = Runtime.getRuntime().exec(arrayOf(su, "-c", "id"))
                if (p.waitFor() == 0) {
                    suBinary = su
                    return SuStyle.DASH_C
                }
            } catch (_: Exception) {}
        }
        return SuStyle.NONE
    }

    private var suBinary: String = "su"

    /** Check if root access is available. */
    fun isRooted(): Boolean = suStyle != SuStyle.NONE

    /** Block all outgoing UDP traffic via iptables. */
    fun blockUdp() {
        execRoot("iptables -A OUTPUT -p udp -j DROP")
    }

    /** Restore outgoing UDP traffic. */
    fun unblockUdp() {
        execRoot("iptables -D OUTPUT -p udp -j DROP")
    }

    /** Add network latency to the primary interface. */
    fun addLatency(ms: Int) {
        execRoot("tc qdisc add dev eth0 root netem delay ${ms}ms")
    }

    /** Remove network latency from the primary interface. */
    fun removeLatency() {
        execRoot("tc qdisc del dev eth0 root")
    }

    /** Enable airplane mode. */
    fun airplaneModeOn() {
        execRoot("settings put global airplane_mode_on 1")
        execRoot("am broadcast -a android.intent.action.AIRPLANE_MODE")
    }

    /** Disable airplane mode. */
    fun airplaneModeOff() {
        execRoot("settings put global airplane_mode_on 0")
        execRoot("am broadcast -a android.intent.action.AIRPLANE_MODE")
    }

    private fun execRoot(command: String): String {
        val args = when (suStyle) {
            SuStyle.NUMERIC -> arrayOf(suBinary, "0", command)
            SuStyle.DASH_C -> arrayOf(suBinary, "-c", command)
            SuStyle.NONE -> throw RuntimeException("Root not available")
        }
        val process = Runtime.getRuntime().exec(args)
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            throw RuntimeException("Root command failed ($exitCode): $command\n$error")
        }
        return output
    }
}
