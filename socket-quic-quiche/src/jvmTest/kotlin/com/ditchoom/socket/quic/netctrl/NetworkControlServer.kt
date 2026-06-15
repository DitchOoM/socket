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
 * Usage: `NetworkControlServerKt [port]`
 * Prints "READY port=<port>" when accepting connections.
 */
class NetworkControlServer(
    private val port: Int = 9998,
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

    private fun reestablishAdbReverse() {
        println("[net-ctrl] Re-establishing adb reverse port mappings")
        ProcessBuilder("adb", "reverse", "tcp:4433", "tcp:4433")
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("adb", "reverse", "tcp:9998", "tcp:9998")
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    private fun adb(shellCommand: String) {
        val result =
            ProcessBuilder("adb", "shell", "su", "0", shellCommand)
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
    val port = args.firstOrNull()?.toIntOrNull() ?: 9998
    NetworkControlServer(port).run()
}
