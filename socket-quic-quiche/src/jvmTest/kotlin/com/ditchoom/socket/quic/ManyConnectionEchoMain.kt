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
    // MEASUREMENT ONLY: -Dintake.yield=<n>|never|wake and -Dintake.capacity=<n> (0 = rendezvous).
    System.getProperty("intake.yield")?.let { rule ->
        IntakeExperiment.readerYield =
            when (rule) {
                "never" -> ReaderYield.Never
                "wake" -> ReaderYield.WhenItWakesTheLoop
                else -> ReaderYield.Every(rule.toInt())
            }
    }
    System.getProperty("intake.capacity")?.let { IntakeExperiment.capacity = it.toInt() }
    System.getProperty("intake.looptakes")?.let { IntakeExperiment.loopTakesPerPass = it.toInt() }
    System.getProperty("intake.rcvbuf")?.let { IntakeExperiment.kernelReceiveBufferBytes = it.toInt() }
    val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    val host =
        object : ManyConnectionHost {
            override val stack = "jvm-ours"

            override fun cpuMicros(): Long = os.processCpuTime / 1000

            override fun maxRssRaw(): Long = 0

            override fun platformCounters(): String = "socket_empty_waits=${com.ditchoom.socket.udp.ReceiveWaits.count.get()}"

            override fun socketDrops(): String {
                // Each row: sk, RefCnt..., local_address:port, ..., drops (the last column), port in hex.
                val byPort =
                    listOf("/proc/net/udp", "/proc/net/udp6")
                        .map(::File)
                        .filter { it.exists() }
                        .flatMap { it.readLines().drop(1) }
                        .mapNotNull { row ->
                            val columns = row.trim().split(Regex("\\s+"))
                            val port = columns.getOrNull(1)?.substringAfterLast(':')?.toIntOrNull(16)
                            val drops = columns.lastOrNull()?.toLongOrNull()
                            if (port == null || drops == null || drops == 0L) null else port to drops
                        }
                val total = byPort.sumOf { it.second }
                val worst = byPort.sortedByDescending { it.second }.take(4).joinToString(",") { "${it.first}:${it.second}" }
                return "sockets_with_drops=${byPort.size} dropped_total=$total worst_port_drops=$worst"
            }

            override fun udpCounters(): String {
                val snmp = File("/proc/net/snmp")
                if (!snmp.exists()) return "unavailable (no /proc/net/snmp)"
                // A dual-stack (IPv6) socket's drops are counted in snmp6, not on the IPv4 line.
                val v6 = File("/proc/net/snmp6").takeIf { it.exists() }?.readLines().orEmpty()
                val udp6 =
                    v6
                        .filter {
                            it.startsWith("Udp6RcvbufErrors") || it.startsWith("Udp6InDatagrams")
                        }.joinToString(" ") { it.replace(Regex("\\s+"), "=") }
                return snmp.readLines().filter { it.startsWith("Udp:") }.joinToString(" | ") + " | " + udp6
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
