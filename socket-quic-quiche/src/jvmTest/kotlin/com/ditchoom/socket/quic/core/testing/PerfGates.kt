package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.loadQuicheApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes every gated number a test checks to `build/perf-gates/<test class>.jsonl`, one JSON object per line, so CI keeps
 * a trend of each (`.github/scripts/perf-trend.py`). A class's file starts over the first time this JVM writes to it.
 * Recording never fails a test: a gate is the assertion, this is its evidence.
 */
internal object PerfGates {
    private val started = ConcurrentHashMap.newKeySet<String>()

    /** What runs these gates: the launcher's JDK, the quiche backend and the socket stack. */
    private val runtime: String by lazy {
        val backend = runCatching { loadQuicheApi()::class.java.simpleName }.getOrDefault("none")
        val stack =
            when (netStackOfThisRuntime) {
                is NetStack.Native -> "native"
                is NetStack.Jdk -> "jdk"
            }
        "\"os\":${quoted(System.getProperty("os.name"))},\"arch\":${quoted(System.getProperty("os.arch"))}," +
            "\"jdk\":${quoted(Runtime.version().feature().toString())},\"backend\":${quoted(backend)},\"stack\":${quoted(stack)}"
    }

    /** Records that [metric] of [test] in [owner] measured [value] against [gate], both in [unit]. */
    fun record(
        owner: Class<*>,
        test: String,
        metric: String,
        value: Double,
        gate: Double,
        unit: String,
    ) {
        val line =
            "{\"test\":${quoted("${owner.simpleName}.$test")},\"metric\":${quoted(metric)},\"value\":${number(value)}," +
                "\"gate\":${number(gate)},\"unit\":${quoted(unit)},$runtime}\n"
        try {
            val file = DIRECTORY.resolve("${owner.simpleName}.jsonl")
            Files.createDirectories(DIRECTORY)
            if (started.add(owner.name)) {
                Files.writeString(file, line)
            } else {
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (failed: java.io.IOException) {
            System.err.println("PerfGates: could not record $metric of $test: $failed")
        }
    }

    private val DIRECTORY: Path = Paths.get(System.getProperty("user.dir"), "build", "perf-gates")

    private fun number(value: Double): String = if (value.isFinite()) "%.4f".format(java.util.Locale.ROOT, value) else "null"

    private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
