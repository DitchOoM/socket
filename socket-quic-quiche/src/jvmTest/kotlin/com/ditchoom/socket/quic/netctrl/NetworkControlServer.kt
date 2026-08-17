package com.ditchoom.socket.quic.netctrl

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Host-side TCP server that executes network manipulation commands on the Android emulator
 * via `adb shell su 0 <command>`. The device test connects and sends [NetCtrlCommand]s;
 * this server responds with [NetCtrlResponse]s.
 *
 * Usage: `NetworkControlServerKt [port] [--serial=<adb serial>]` — port defaults to 0, meaning
 * OS-assigned. Prints "READY port=<port>" with the **bound** port when accepting connections;
 * callers parse it from there instead of hardcoding a constant on both sides.
 *
 * [adbSerial] pins every `adb` invocation to one device with `-s`. Bare `adb` fails outright when
 * more than one device is attached, which is not a hypothetical: a developer with an emulator *and*
 * a handset plugged in gets `error: more than one device/emulator` from a server whose entire job is
 * running adb commands, and every impairment silently becomes a no-op (this class logs adb failures
 * as "non-fatal").
 */
class NetworkControlServer(
    private val port: Int = 0,
    private val adbSerial: String? = null,
) {
    private val serverSocket = ServerSocket(port)
    private val appliedModifications = ConcurrentLinkedQueue<String>()
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "net-ctrl-scheduler").apply { isDaemon = true }
        }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread { cleanup() })

        println("READY port=${serverSocket.localPort}")
        System.out.flush()

        while (!serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                println("[net-ctrl] Client connected: ${client.remoteSocketAddress}")
                handleClient(client)
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 0 // no timeout on server side — wait for commands indefinitely
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            while (!client.isClosed) {
                val command =
                    try {
                        NetCtrlFraming.recv(inp, NetCtrlCommandCodec)
                    } catch (_: IOException) {
                        break
                    }

                println("[net-ctrl] << $command")
                val response = dispatch(command)
                println("[net-ctrl] >> $response")

                try {
                    NetCtrlFraming.send(out, NetCtrlResponseCodec, response)
                } catch (_: IOException) {
                    break // client disconnected (e.g., airplane mode)
                }
            }
        } catch (e: Exception) {
            println("[net-ctrl] Client error: ${e.message}")
        } finally {
            client.close()
            println("[net-ctrl] Client disconnected, running cleanup")
            cleanup()
        }
    }

    private fun dispatch(command: NetCtrlCommand): NetCtrlResponse =
        when (command) {
            is NetCtrlCommand.BlockUdp -> {
                adb("iptables -A OUTPUT -p udp -j DROP")
                track("iptables -D OUTPUT -p udp -j DROP")
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.UnblockUdp -> {
                adb("iptables -D OUTPUT -p udp -j DROP")
                untrack("iptables -D OUTPUT -p udp -j DROP")
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.AddLatency -> {
                adb("tc qdisc add dev eth0 root netem delay ${command.ms}ms")
                track("tc qdisc del dev eth0 root")
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.RemoveLatency -> {
                adb("tc qdisc del dev eth0 root")
                untrack("tc qdisc del dev eth0 root")
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.AirplaneOn -> {
                adb("settings put global airplane_mode_on 1")
                adb("am broadcast -a android.intent.action.AIRPLANE_MODE")
                track("settings put global airplane_mode_on 0")
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.AirplaneOff -> {
                airplaneOff()
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.ScheduleAirplaneOff -> {
                scheduler.schedule({
                    println("[net-ctrl] Scheduled AIRPLANE_OFF firing")
                    airplaneOff()
                }, command.delayMs, TimeUnit.MILLISECONDS)
                NetCtrlResponse.Scheduled(command.delayMs)
            }
            is NetCtrlCommand.Cleanup -> {
                cleanup()
                NetCtrlResponse.Ok()
            }
            is NetCtrlCommand.Ping -> {
                NetCtrlResponse.Ok()
            }
        }

    private fun airplaneOff() {
        adb("settings put global airplane_mode_on 0")
        adb("am broadcast -a android.intent.action.AIRPLANE_MODE")
        untrack("settings put global airplane_mode_on 0")
        // Re-establish adb reverse mappings — airplane mode clears them
        Thread.sleep(1000) // wait for network stack to settle
        reestablishAdbReverse()
    }

    /**
     * Restore the one mapping this server owns: its **own** bound port, and only if it is gone.
     *
     * It used to re-establish `tcp:4433` and `tcp:9998` — two constants, both wrong by the time this
     * ran. 9998 was the legacy control port from before the server started binding 0, so after
     * airplane mode the recovery restored a mapping to a port nothing was listening on while the
     * mapping the device actually needed stayed dropped. 4433 was the old QUIC port, which never
     * needed a mapping at all: `adb reverse` forwards **TCP** and QUIC is UDP. Neither number can be
     * known ahead of time or is needed, so neither is written down.
     *
     * ## Why it asks first
     * "Restore" is not "re-register". Re-registering a mapping that is still **live** corrupts it:
     * measured on an SM-F956U1, where airplane mode never actually fires (the device is not rooted,
     * so `su 0 settings put …` is a no-op) and the mapping was therefore still up. After the
     * unconditional re-add, every subsequent device connection arrived from the impossible peer
     * address `/1.0.0.0:55917` — the *same* source port four times running — and although this server
     * read the `Ping` and wrote `Ok`, the reply never reached the device, which timed out. Four
     * migration tests skipped with "net-ctrl did not answer", on a channel that had answered.
     *
     * `adb reverse --list` costs one process and turns the operation idempotent: the airplane-mode
     * case (mapping genuinely dropped) re-adds, and the case where nothing was dropped leaves a
     * working channel alone. Removing-then-adding would also be idempotent but would tear down the
     * live channel for no reason on exactly the hosts where nothing needed restoring.
     */
    private fun reestablishAdbReverse() {
        val boundPort = serverSocket.localPort
        val mapping = "tcp:$boundPort"
        if (adbReverseListContains(mapping)) {
            println("[net-ctrl] adb reverse $mapping still mapped — leaving it alone (re-adding a live mapping breaks it)")
            return
        }
        println("[net-ctrl] Re-establishing adb reverse $mapping (the control channel)")
        ProcessBuilder(adbCommand("reverse", mapping, mapping))
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    /**
     * Whether adb still lists [mapping] as reversed for this device.
     *
     * A failed/empty `adb reverse --list` answers "no", which lands on the re-add path — the safe
     * direction, since that is the branch that was unconditional before.
     */
    private fun adbReverseListContains(mapping: String): Boolean =
        try {
            val process =
                ProcessBuilder(adbCommand("reverse", "--list"))
                    .redirectErrorStream(true)
                    .start()
            val listing =
                process.inputStream
                    .bufferedReader()
                    .readText()
            process.waitFor()
            // `adb reverse --list` prints `<transport> <remote> <local>`, e.g. `UsbFfs tcp:64779 tcp:64779`.
            listing.lineSequence().any { it.split(Regex("\\s+")).contains(mapping) }
        } catch (e: Exception) {
            println("[net-ctrl]   adb reverse --list failed (${e.message}) — assuming the mapping is gone")
            false
        }

    /** `adb`, pinned to [adbSerial] when one was supplied. */
    private fun adbCommand(vararg args: String): List<String> =
        buildList {
            add("adb")
            if (adbSerial != null) {
                add("-s")
                add(adbSerial)
            }
            addAll(args)
        }

    private fun adb(shellCommand: String) {
        val result =
            ProcessBuilder(adbCommand("shell", "su", "0", shellCommand))
                .redirectErrorStream(true)
                .start()
        val output =
            result.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val exitCode = result.waitFor()
        if (output.isNotEmpty()) println("[net-ctrl]   adb: $output")
        if (exitCode != 0) println("[net-ctrl]   adb exit=$exitCode (non-fatal)")
    }

    private fun track(reverseCommand: String) {
        appliedModifications.add(reverseCommand)
    }

    private fun untrack(reverseCommand: String) {
        appliedModifications.remove(reverseCommand)
    }

    private fun cleanup() {
        val mods = appliedModifications.toList()
        appliedModifications.clear()
        if (mods.isEmpty()) return
        println("[net-ctrl] Cleaning up ${mods.size} modification(s)")
        for (reverseCmd in mods.reversed()) {
            try {
                adb(reverseCmd)
            } catch (e: Exception) {
                println("[net-ctrl]   cleanup failed: ${e.message}")
            }
        }
    }
}

fun main(args: Array<String>) {
    // 0 = OS-assigned. [run] prints the bound port, which the caller parses; a pinned default made
    // "the port is taken" a failure mode that only existed because of the constant.
    val port = args.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: 0
    // Falls back to ANDROID_SERIAL, which adb honours natively — so the two ways of pinning a device
    // agree instead of one silently overriding the other.
    val serial =
        args
            .firstOrNull { it.startsWith("--serial=") }
            ?.removePrefix("--serial=")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }
    NetworkControlServer(port, serial).run()
}
