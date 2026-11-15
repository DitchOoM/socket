package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [manyConnectionEcho] on the JVM, for benchmarks/native-compare/run.py: the JVM twin of `NativeCompareBench`'s
 * `qconns` workload, run from the quic-echo jar.
 *
 * args: certificate path, key path, connections, warm-up seconds, measured seconds, servers (default 1). Exits
 * non-zero when a connection fails, so a failed run never waits on its other connections.
 */
fun main(args: Array<String>) {
    val tls = QuicTlsConfig(certChainPath = args[0], privKeyPath = args[1])
    val options =
        QuicOptions(
            alpnProtocols = listOf("bench"),
            verifyPeer = false,
            idleTimeout = 10.minutes,
            migration = MigrationPolicy.Forbidden,
            flowControl =
                FlowControl(
                    initialMaxData = 64L * 1024 * 1024,
                    initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                    initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                    initialMaxStreamsBidi = 1_000_000,
                ),
        )
    val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    val host =
        object : ManyConnectionHost {
            override val stack = "jvm-ours"

            override fun cpuMicros(): Long = os.processCpuTime / 1000

            override fun maxRssRaw(): Long = 0

            override fun udpCounters(): String {
                val snmp = File("/proc/net/snmp")
                if (!snmp.exists()) return "unavailable (no /proc/net/snmp)"
                return snmp.readLines().filter { it.startsWith("Udp:") }.joinToString(" | ")
            }
        }
    val code =
        try {
            runBlocking(Dispatchers.Default) {
                manyConnectionEcho(
                    host,
                    conns = args[2].toInt(),
                    servers = args.getOrElse(5) { "1" }.toInt(),
                    warmup = args[3].toLong().seconds,
                    window = args[4].toLong().seconds,
                    tls = tls,
                    options = options,
                )
            }
            0
        } catch (failure: Throwable) {
            failure.printStackTrace()
            1
        }
    exitProcess(code)
}
