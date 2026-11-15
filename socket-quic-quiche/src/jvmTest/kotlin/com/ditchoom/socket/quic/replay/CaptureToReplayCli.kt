package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.QuicRole
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/** Why the command line does not name a capture. */
private sealed interface CliRefusal {
    data class UnknownOption(
        val option: String,
    ) : CliRefusal

    data class OptionWithoutValue(
        val option: String,
    ) : CliRefusal

    data class MissingOption(
        val option: String,
    ) : CliRefusal

    /** [directory] holds [count] kept traces, so `--trace` must say which. */
    data class CellTraceNotSingle(
        val directory: File,
        val count: Int,
    ) : CliRefusal

    data class NotARole(
        val value: String,
    ) : CliRefusal

    data class NotMilliseconds(
        val value: String,
    ) : CliRefusal
}

private sealed interface Cli {
    class Inputs(
        val inputs: CaptureInputs,
        val out: File,
    ) : Cli

    data class Refused(
        val refusal: CliRefusal,
    ) : Cli
}

private val OPTIONS = setOf("name", "source", "role", "cell", "trace", "qlog", "pcap-ours", "pcap-peer", "one-way-ms", "out")

private const val USAGE = """capture -> replay schedule

  --name <words-with-dashes>   names the schedule, its file and its val            (required)
  --cell <dir>                 a quic-interop-runner cell our client ran in: <dir>/client/trace/*.trace,
                               <dir>/client/qlog, <dir>/sim/trace_node_{left,right}.pcap
  --trace <file>               our endpoint's replay trace (required without --cell, or when the cell kept several)
  --qlog <file>                our endpoint's qlog for that connection
  --pcap-ours <file>           a capture taken on our side of the lossy link
  --pcap-peer <file>           a capture taken on the peer's side of it
  --one-way-ms <n>             without captures: the path's one-way delay (default 15)
  --role client|server         which end our endpoint was (default client)
  --source <text>              where the capture came from, for the generated header
  --out <dir>                  where the schedule's .kt goes (default: the replay fixtures directory)
"""

/** An option as the command line gave it. */
private sealed interface Option {
    data class Given(
        val value: String,
    ) : Option

    data object Absent : Option
}

private fun parse(args: Array<String>): Cli {
    val given = mutableMapOf<String, String>()
    var at = 0
    while (at < args.size) {
        val option = args[at].removePrefix("--")
        if (option == args[at] || option !in OPTIONS) return Cli.Refused(CliRefusal.UnknownOption(args[at]))
        if (at + 1 >= args.size) return Cli.Refused(CliRefusal.OptionWithoutValue(args[at]))
        given[option] = args[at + 1]
        at += 2
    }

    fun option(name: String): Option = if (name in given) Option.Given(given.getValue(name)) else Option.Absent

    fun file(
        name: String,
        inCell: String,
    ): List<File> =
        when (val explicit = option(name)) {
            is Option.Given -> listOf(File(explicit.value))
            Option.Absent ->
                when (val cell = option("cell")) {
                    is Option.Given -> listOf(File(cell.value).resolve(inCell))
                    Option.Absent -> emptyList()
                }
        }

    val name =
        when (val named = option("name")) {
            is Option.Given -> named.value
            Option.Absent -> return Cli.Refused(CliRefusal.MissingOption("--name"))
        }
    val role =
        when (val value = given.getOrElse("role") { "client" }) {
            "client" -> QuicRole.Client
            "server" -> QuicRole.Server
            else -> return Cli.Refused(CliRefusal.NotARole(value))
        }
    val trace =
        when (val explicit = option("trace")) {
            is Option.Given -> File(explicit.value)
            Option.Absent ->
                when (val cell = option("cell")) {
                    Option.Absent -> return Cli.Refused(CliRefusal.MissingOption("--trace"))
                    is Option.Given -> {
                        val kept = File(cell.value).resolve("client/trace").listFiles { file -> file.name.endsWith(".trace") }.orEmpty()
                        if (kept.size != 1) return Cli.Refused(CliRefusal.CellTraceNotSingle(File(cell.value), kept.size))
                        kept.single()
                    }
                }
        }
    val captures = file("pcap-ours", "sim/trace_node_left.pcap") + file("pcap-peer", "sim/trace_node_right.pcap")
    val link =
        if (captures.size == 2) {
            LinkEvidence.BothEnds(captures.first(), captures.last())
        } else {
            val text = given.getOrElse("one-way-ms") { "15" }
            if (text.isEmpty() || text.any { !it.isDigit() }) return Cli.Refused(CliRefusal.NotMilliseconds(text))
            LinkEvidence.OurRecordsOnly(text.toLong().milliseconds)
        }
    val qlog =
        when (val explicit = option("qlog")) {
            is Option.Given -> QlogEvidence.Recorded(File(explicit.value))
            Option.Absent ->
                when (val cell = option("cell")) {
                    is Option.Given -> qlogOfConnection(File(cell.value).resolve("client/qlog"), trace)
                    Option.Absent -> QlogEvidence.Absent
                }
        }
    val out = File(given.getOrElse("out") { "src/sharedQuicheTestSuites/kotlin/com/ditchoom/socket/quic/replay/fixtures" })
    return Cli.Inputs(CaptureInputs(name, given.getOrElse("source") { trace.path }, role, trace, link, qlog), out)
}

/** The qlog in [directory] named for the source connection ID of [trace]'s first datagram, as our client names them. */
private fun qlogOfConnection(
    directory: File,
    trace: File,
): QlogEvidence {
    val firstSent = trace.useLines { lines -> lines.filter { " DGRAM_OUT " in it }.take(1).toList() }
    val source = firstSent.map { it.substringAfterLast(' ') }.flatMap { sourceConnectionId(it) }
    return source
        .flatMap { id -> directory.listFiles { file -> file.name.contains(id) && file.name.endsWith("qlog") }.orEmpty().take(1) }
        .fold<File, QlogEvidence>(QlogEvidence.Absent) { _, file -> QlogEvidence.Recorded(file) }
}

/** The source connection ID of the long-header packet [hex] starts with (RFC 9000 §17.2): one, or none when cut short. */
private fun sourceConnectionId(hex: String): List<String> {
    val bytes =
        hex
            .chunked(2)
            .filter {
                it.length == 2 &&
                    it.all { c ->
                        c.isDigit() || c.lowercaseChar() in 'a'..'f'
                    }
            }.map { it.toInt(16) }
    if (bytes.size < 7) return emptyList()
    val sourceLengthAt = 6 + bytes[5]
    if (bytes.size <= sourceLengthAt) return emptyList()
    val from = sourceLengthAt + 1
    val until = from + bytes[sourceLengthAt]
    if (bytes.size < until) return emptyList()
    return listOf(bytes.subList(from, until).joinToString("") { "%02x".format(it) })
}

private fun describe(refusal: CliRefusal): String =
    when (refusal) {
        is CliRefusal.UnknownOption -> "unknown option ${refusal.option}"
        is CliRefusal.OptionWithoutValue -> "${refusal.option} needs a value"
        is CliRefusal.MissingOption -> "${refusal.option} is required"
        is CliRefusal.CellTraceNotSingle -> "${refusal.directory}/client/trace holds ${refusal.count} traces: name one with --trace"
        is CliRefusal.NotARole -> "--role is client or server, not ${refusal.value}"
        is CliRefusal.NotMilliseconds -> "--one-way-ms is a whole number of milliseconds, not ${refusal.value}"
    }

private fun describe(refusal: ExtractionRefusal): String =
    when (refusal) {
        ExtractionRefusal.TraceHoldsNoDatagramOfOurs -> "the trace holds no datagram our endpoint sent"
        is ExtractionRefusal.CaptureUnreadable -> "${refusal.file} is not a capture this reads: ${refusal.fault}"
        is ExtractionRefusal.ConnectionNotInCapture -> "${refusal.file} holds no datagram with the bytes of the trace's first"
        is ExtractionRefusal.TraceLineUnreadable -> "${refusal.file} line ${refusal.line} is not a trace line"
        is ExtractionRefusal.QlogUnreadable -> "${refusal.file} line ${refusal.line} is not JSON"
        is ExtractionRefusal.QlogDoesNotMatchTrace ->
            "the qlog's sent packets stop matching the trace's datagrams at our datagram ${refusal.ordinal}: " +
                "another connection's qlog, or a cut one"
    }

/** `words-with-dashes` as `wordsWithDashes`. */
private fun valName(name: String): String =
    name
        .split('-', ' ', '_', '.')
        .filter { it.isNotEmpty() }
        .mapIndexed { index, word ->
            if (index ==
                0
            ) {
                word.replaceFirstChar { it.lowercase() }
            } else {
                word.replaceFirstChar { it.uppercase() }
            }
        }.joinToString("")

/**
 * Capture to replay test: writes the schedule of one captured connection as a Kotlin fixture and prints
 * the test that replays it. `./gradlew :socket-quic-quiche:captureToReplay -Pcapture="--name … --cell …"`.
 */
fun main(args: Array<String>) {
    val cli =
        when (val parsed = parse(args)) {
            is Cli.Inputs -> parsed
            is Cli.Refused -> {
                System.err.println(describe(parsed.refusal))
                System.err.println(USAGE)
                exitProcess(2)
            }
        }
    val extracted =
        when (val extraction = CaptureToSchedule.extract(cli.inputs)) {
            is Extraction.Extracted -> extraction
            is Extraction.Refused -> {
                System.err.println(describe(extraction.refusal))
                exitProcess(1)
            }
        }
    val name = valName(cli.inputs.name)
    val file = cli.out.resolve(name.replaceFirstChar { it.uppercase() } + ".kt")
    cli.out.mkdirs()
    file.writeText(CaptureToSchedule.generateKotlin(extracted, name, cli.inputs.source))
    println("wrote $file")
    println("paste into CaptureReplayTestSuite:")
    println()
    println(CaptureToSchedule.generateTestSkeleton(extracted, name))
}
