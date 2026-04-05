package com.ditchoom.socket.quic

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utilities for manipulating network state on a rooted Android emulator.
 *
 * All commands require root access (`su`). Call [isRooted] first to verify.
 */
object EmulatorNetworkUtils {
    /** Check if root access is available. */
    fun isRooted(): Boolean =
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = process.waitFor()
            result == 0
        } catch (_: Exception) {
            false
        }

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
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
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
