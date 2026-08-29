package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Hunt instrument for #450: what does seed 4 actually perturb, and which single decision produces
 * the `ByLocal(IdleTimeout)` during the handshake?
 */
class Hunt450SeedBisectTests {
    private data class Row(
        val state: String,
        val atMs: Long,
    )

    private data class Run(
        val outcome: String,
        val closedAtMs: Long,
        val observations: List<ImpairedPipe.Observation>,
        val clientStates: List<Row>,
        val serverStates: List<Row>,
    )

    private fun describe(bytes: ByteArray): String {
        val parts = ArrayList<String>()
        var off = 0
        while (off < bytes.size) {
            val b0 = bytes[off].toInt() and 0xff
            if (b0 and 0x80 == 0) {
                parts += "1RTT(${bytes.size - off})"
                break
            }
            if (bytes.size - off < 7) {
                parts += "?"
                break
            }
            val type = (b0 shr 4) and 0x3
            var p = off + 5
            val dcidLen = bytes[p].toInt() and 0xff
            p += 1 + dcidLen
            val scidLen = bytes[p].toInt() and 0xff
            p += 1 + scidLen
            fun varint(): Long {
                val first = bytes[p].toInt() and 0xff
                val len = 1 shl (first shr 6)
                var v = (first and 0x3f).toLong()
                for (i in 1 until len) v = (v shl 8) or (bytes[p + i].toLong() and 0xff)
                p += len
                return v
            }
            val name =
                when (type) {
                    0 -> {
                        val tokenLen = varint()
                        p += tokenLen.toInt()
                        "Initial"
                    }
                    1 -> "0RTT"
                    2 -> "Handshake"
                    else -> "Retry"
                }
            if (type == 3) {
                parts += "Retry(${bytes.size - off})"
                break
            }
            val length = varint()
            val pktEnd = p + length.toInt()
            parts += "$name(${pktEnd - off})"
            off = pktEnd
            if (length <= 0) break
        }
        return parts.joinToString("+")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runSeed(
        seed: Long,
        loss: Double = 0.30,
        latency: Duration = 5.milliseconds,
        idleTimeout: Duration = 2.seconds,
        forceDeliver: Set<Int> = emptySet(),
        forceDrop: Set<Int> = emptySet(),
        bound: Duration = 60.seconds,
    ): Run =
        withSemanticSim(
            ImpairmentConfig(seed = seed, loss = loss, latency = latency, forceDeliver = forceDeliver, forceDrop = forceDrop),
            quicOptions = semanticSimOptions(idleTimeout = idleTimeout),
            establishTimeout = bound,
            clock = SimClock(testScheduler),
            gate = EstablishmentGate.None,
        ) {
            val clientStates = ArrayList<Row>()
            val serverStates = ArrayList<Row>()
            val c = launch { clientDriver.state.collect { clientStates += Row(it.toString(), testScheduler.currentTime) } }
            val s = launch { serverDriver.state.collect { serverStates += Row(it.toString(), testScheduler.currentTime) } }
            val started = testScheduler.currentTime
            val settled = withTimeoutOrNull(bound) { clientDriver.state.first { it !is QuicConnectionState.Handshaking } }
            val closedAt = testScheduler.currentTime - started
            // Let the server side observe a little more (it may still be retransmitting).
            c.cancel()
            s.cancel()
            val outcome =
                when (settled) {
                    null -> "STILL_HANDSHAKING"
                    is QuicConnectionState.Established -> "Established"
                    is QuicConnectionState.Closed ->
                        if (settled.reason == QuicCloseReason.ByLocal(QuicError.IdleTimeout)) "LOCAL_IDLE_TIMEOUT" else "Closed(${settled.reason})"
                    else -> settled.toString()
                }
            Run(outcome, closedAt, pipe.observations(), clientStates, serverStates)
        }

    private fun render(run: Run): String =
        buildString {
            appendLine("  outcome=${run.outcome} settledAt=${run.closedAtMs}ms")
            run.observations.forEach { o ->
                val fate = if (o.dropped) "DROP" else "ok  "
                val forced = if (o.dropped != o.seededDrop) " (FORCED)" else ""
                appendLine("  #${o.index.toString().padStart(2)} t=${o.atMs.toString().padStart(5)}ms ${o.side} $fate len=${o.len.toString().padStart(4)} ${describe(o.bytes)}$forced")
            }
            appendLine("  client states: ${run.clientStates}")
            appendLine("  server states: ${run.serverStates}")
        }

    @Test
    fun traceSeed4AndAPassingSeed() =
        runTest(timeout = 300.seconds) {
            val four = runSeed(4L)
            println("[#450 hunt] seed 4 (loss=0.30, latency=5ms, idle=2s):")
            println(render(four))
            val one = runSeed(1L)
            println("[#450 hunt] seed 1 (control):")
            println(render(one))
            assertTrue(four.outcome == "LOCAL_IDLE_TIMEOUT", "seed 4 is expected to reproduce; got ${four.outcome}")
        }

    @Test
    fun bisectSeed4() =
        runTest(timeout = 600.seconds) {
            val base = runSeed(4L)
            val dropped = base.observations.filter { it.dropped }.map { it.index }
            println("[#450 hunt] seed 4 baseline=${base.outcome} at ${base.closedAtMs}ms; seeded drops=$dropped")
            // Shared-RNG pinning shifts every later draw, so pin decisions with the RNG taken out of the
            // picture instead: loss = 0 (everything delivered) and an explicit drop script by index.
            fun script(vararg drops: Int) = drops.toSet()
            val scripts =
                listOf(
                    "nothing dropped" to script(),
                    "drop #0 only (first Initial)" to script(0),
                    "drop #1 only (PTO#1 retransmit)" to script(1),
                    "drop #0 and #1 (seed 4's exact pattern)" to script(0, 1),
                    "drop #0, #1, #2" to script(0, 1, 2),
                )
            for ((name, drops) in scripts) {
                val r = runSeed(4L, loss = 0.0, forceDrop = drops)
                println("[#450 hunt] loss=0 idle=2s  $name -> ${r.outcome} at ${r.closedAtMs}ms")
                println(render(r))
            }
            println("[#450 hunt] same drop script, idle=10s (the loopback suite's value): does the third transmission get out?")
            for ((name, drops) in scripts) {
                val r = runSeed(4L, loss = 0.0, forceDrop = drops, idleTimeout = 10.seconds)
                println("[#450 hunt] loss=0 idle=10s $name -> ${r.outcome} at ${r.closedAtMs}ms")
                println(render(r))
            }
        }

    @Test
    fun qlogSeed4() =
        runTest(timeout = 300.seconds) {
            val dir = File(System.getProperty("hunt450.qlog.dir") ?: (System.getProperty("java.io.tmpdir") + "/hunt450-qlog"))
            dir.mkdirs()
            val before = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            System.setProperty("quic.qlog.dir", dir.absolutePath)
            try {
                val four = runSeed(4L)
                println("[#450 hunt qlog] seed 4 -> ${four.outcome} at ${four.closedAtMs}ms; qlogs in ${dir.absolutePath}")
                val afterFour = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                println("[#450 hunt qlog] seed 4 files: ${afterFour - before}")
                val one = runSeed(1L)
                val afterOne = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                println("[#450 hunt qlog] seed 1 -> ${one.outcome} at ${one.closedAtMs}ms; files: ${afterOne - afterFour}")
            } finally {
                System.clearProperty("quic.qlog.dir")
            }
        }
}
