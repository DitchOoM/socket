package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/** Why the command line names no recording. */
private sealed interface CliRefusal {
    data class UnknownOption(
        val option: String,
    ) : CliRefusal

    data class MissingOption(
        val option: String,
    ) : CliRefusal

    data class NotAName(
        val name: String,
    ) : CliRefusal

    data class NotMilliseconds(
        val value: String,
    ) : CliRefusal

    data class NotConverted(
        val why: Refusal,
    ) : CliRefusal
}

private const val USAGE = """walk recording -> conditions fixture + replay test

  --trace <file>     our endpoint's recording of the connection (v1 trace grammar)            (required)
  --name <TestName>  names the fixture's val and the test class (letters and digits)          (required)
  --peer <file>      the peer's recording of the same connection
  --from-ms <n>      the window's start on the recording's clock (with --to-ms); default: around its disruptions
  --to-ms <n>
  --source <text>    where the recording came from, for the fixture's header (no addresses)
  --out <dir>        the conditions test directory (default: this module's)
"""

private val OPTIONS = setOf("trace", "name", "peer", "from-ms", "to-ms", "source", "out")

private const val DEFAULT_OUT = "src/jvmTest/kotlin/com/ditchoom/socket/quic/replay/conditions"

/**
 * One command from a walk failure to a permanent test: converts the recording to [Conditions], replays them against
 * the core as it is, and writes the fixture and a test that holds the core to the outcome it had. Run through
 * `./gradlew :socket-quic-quiche:conditionsFromTrace -Ptrace=<file> -Pname=<TestName>`.
 */
fun main(args: Array<String>) {
    val given = HashMap<String, String>()
    var at = 0
    while (at < args.size) {
        val option = args[at].removePrefix("--")
        if (option !in OPTIONS || at + 1 >= args.size) refuse(CliRefusal.UnknownOption(args[at]))
        given[option] = args[at + 1]
        at += 2
    }
    val trace = File(given.getOrElse("trace") { refuse(CliRefusal.MissingOption("--trace")) })
    val name = given.getOrElse("name") { refuse(CliRefusal.MissingOption("--name")) }
    if (!name.matches(Regex("[A-Z][A-Za-z0-9]*"))) refuse(CliRefusal.NotAName(name))
    val window =
        if ("from-ms" in given && "to-ms" in given) {
            Window.Between(millis(given.getValue("from-ms")), millis(given.getValue("to-ms")))
        } else {
            Window.AroundDisruptions()
        }
    val peer = if ("peer" in given) TraceEvent.parseAll(File(given.getValue("peer")).readLines()) else emptyList()
    val source = given.getOrElse("source") { "walk recording ${trace.name}" }
    val converted = RecordingToConditions.convert(name, source, TraceEvent.parseAll(trace.readLines()), peer, window)
    val conditions =
        when (converted) {
            is Converted.Refused -> refuse(CliRefusal.NotConverted(converted.why))
            is Converted.Script -> converted.conditions
        }
    val replayed = ConditionReplay(conditions).run()
    val valName = name.replaceFirstChar { it.lowercase() }
    val out = File(given.getOrElse("out") { DEFAULT_OUT })
    File(out, "fixtures").mkdirs()
    File(out, "fixtures/$name.kt").writeText(ConditionsCodegen.fixture(conditions, valName))
    File(
        out,
        "${name}Tests.kt",
    ).writeText(ConditionsCodegen.test("${name}Tests", valName, ConditionsCodegen.expectOf(replayed.outcome, conditions)))
    println("wrote ${File(out, "fixtures/$name.kt")} and ${File(out, "${name}Tests.kt")}")
    println(replayed.describe())
    exitProcess(0)
}

private fun millis(value: String) =
    if (value.matches(Regex("[0-9]+"))) value.toLong().milliseconds else refuse(CliRefusal.NotMilliseconds(value))

private fun refuse(why: CliRefusal): Nothing {
    System.err.println("conditionsFromTrace: $why\n\n$USAGE")
    exitProcess(2)
}
