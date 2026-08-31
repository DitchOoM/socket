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
    /**
     * How a privileged device command is run. A seam, because every interesting state of this class
     * is one `adb` cannot be asked to produce on demand — an unrooted device, a wrong serial, adb
     * missing — and #389 is exactly what an untestable decision costs: five migration tests passed
     * against a healthy network for months.
     */
    private val shell: DeviceShell = DeviceShell.adb(adbSerial),
) {
    // Lazy: constructing a server must not bind a port. NetworkControlServerCapabilityTests builds
    // one purely to drive `dispatch`, and a socket opened in the constructor would be a real resource
    // held by a unit test that never calls run().
    private val serverSocket by lazy { ServerSocket(port) }
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

    /**
     * Run [shellCommand] and answer for it: [NetCtrlResponse.Ok] only when it actually ran.
     *
     * This is the heart of #389. Every impairment used to call `adb(...)`, ignore what came back, and
     * return `Ok` unconditionally — so on an unrooted device `su: inaccessible or not found` was
     * printed as "non-fatal" and the caller was told the network had been impaired. A response that
     * cannot say "no" is not a response.
     */
    private fun applying(vararg shellCommands: String): NetCtrlResponse {
        for (shellCommand in shellCommands) {
            when (val outcome = shell.run(shellCommand)) {
                is ShellOutcome.Ran -> Unit
                is ShellOutcome.Failed ->
                    return NetCtrlResponse.Error(
                        "`$shellCommand` failed (exit ${outcome.exitCode}): ${outcome.output.ifEmpty { "<no output>" }}",
                    )
            }
        }
        return NetCtrlResponse.Ok()
    }

    /**
     * Whether this host can run privileged commands on the device, decided once and remembered.
     *
     * `su 0 id` is the question the impairments are really asking, and it is asked directly rather
     * than inferred from an impairment's exit code — an `iptables` rule can fail for reasons that
     * have nothing to do with root, and conflating the two would make the capability answer wrong in
     * both directions.
     */
    private val impairmentCapability: NetCtrlResponse by lazy {
        when (val outcome = shell.run("id")) {
            is ShellOutcome.Ran -> NetCtrlResponse.ImpairmentAvailable()
            is ShellOutcome.Failed ->
                NetCtrlResponse.ImpairmentUnavailable(
                    "privileged device commands are unavailable: `su 0 id` exited ${outcome.exitCode}" +
                        " (${outcome.output.ifEmpty { "<no output>" }}). Impairment needs a rooted device;" +
                        " iptables/tc/airplane-mode cannot be applied on this one.",
                )
        }
    }

    /**
     * Drive one command without a socket — the seam [NetworkControlServerCapabilityTests] uses.
     *
     * Named for what it is rather than widening [dispatch]: the tests need to assert what the server
     * *answers*, and the answer is the whole of #389.
     */
    internal fun dispatchForTest(command: NetCtrlCommand): NetCtrlResponse = dispatch(command)

    /** The undo commands currently tracked for cleanup, oldest first. */
    internal fun trackedForTest(): List<String> = appliedModifications.toList()

    private fun dispatch(command: NetCtrlCommand): NetCtrlResponse =
        when (command) {
            is NetCtrlCommand.BlockUdp ->
                applying("iptables -A OUTPUT -p udp -j DROP")
                    .alsoTrackingOnSuccess("iptables -D OUTPUT -p udp -j DROP")
            is NetCtrlCommand.UnblockUdp ->
                applying("iptables -D OUTPUT -p udp -j DROP")
                    .alsoUntrackingOnSuccess("iptables -D OUTPUT -p udp -j DROP")
            is NetCtrlCommand.AddLatency ->
                applying("tc qdisc add dev eth0 root netem delay ${command.ms}ms")
                    .alsoTrackingOnSuccess("tc qdisc del dev eth0 root")
            is NetCtrlCommand.RemoveLatency ->
                applying("tc qdisc del dev eth0 root")
                    .alsoUntrackingOnSuccess("tc qdisc del dev eth0 root")
            is NetCtrlCommand.AirplaneOn ->
                applying(
                    "settings put global airplane_mode_on 1",
                    "am broadcast -a android.intent.action.AIRPLANE_MODE",
                ).alsoTrackingOnSuccess("settings put global airplane_mode_on 0")
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
            is NetCtrlCommand.QueryImpairment -> impairmentCapability
        }

    /** Record the undo for a modification that actually applied — and only then. */
    private fun NetCtrlResponse.alsoTrackingOnSuccess(reverseCommand: String): NetCtrlResponse =
        also { if (it is NetCtrlResponse.Ok) track(reverseCommand) }

    /**
     * Forget an undo whose removal actually applied — and only then.
     *
     * Untracking on a failed removal would drop the cleanup for a rule still installed on the device,
     * leaving it behind for every later test on that emulator.
     */
    private fun NetCtrlResponse.alsoUntrackingOnSuccess(reverseCommand: String): NetCtrlResponse =
        also { if (it is NetCtrlResponse.Ok) untrack(reverseCommand) }

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

    /**
     * Run [shellCommand] on the device, ignoring how it went.
     *
     * The remaining callers are the ones where there is genuinely nothing to report to: cleanup on a
     * shutdown hook, and the scheduled airplane-mode recovery, which fires long after the caller's
     * connection is gone. Anything a client is waiting on goes through [applying] instead.
     */
    private fun adb(shellCommand: String) {
        when (val outcome = shell.run(shellCommand)) {
            is ShellOutcome.Ran -> if (outcome.output.isNotEmpty()) println("[net-ctrl]   adb: ${outcome.output}")
            is ShellOutcome.Failed ->
                println("[net-ctrl]   adb exit=${outcome.exitCode} (nobody is waiting on this one): ${outcome.output}")
        }
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
