package com.ditchoom.socket.quic

/** One line of an NSS key log: `<label> <client random, hex> <secret, hex>`. */
data class NssKeyLogEntry(
    val label: String,
    val clientRandom: String,
    val secret: String,
)

object NssKeyLog {
    private val line = Regex("^([A-Z0-9_]+) ([0-9a-f]{64}) ([0-9a-f]+)$")

    /** Every entry in [text]; a line that is not NSS key log format fails loudly with the whole file. */
    fun parse(text: String): List<NssKeyLogEntry> =
        text.lines().filter { it.isNotEmpty() }.map { raw ->
            val match = line.matchEntire(raw) ?: throw AssertionError("not an NSS key log line: \"$raw\" in:\n$text")
            val (label, random, secret) = match.destructured
            NssKeyLogEntry(label, random, secret)
        }
}
