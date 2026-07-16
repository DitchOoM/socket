package com.ditchoom.socket.quic.trace.tools

import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry point behind the `:socket-quic-trace-tools:retraceQuicTrace` Gradle task: reads a
 * captured `v1` QUIC trace and an R8/ProGuard `mapping.txt`, rewrites the STATE/ERROR class-name
 * tokens to their original names, and writes the deobfuscated trace out.
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println(
            """
            Usage: retraceQuicTrace <traceIn> <mapping.txt> <traceOut>

            Rewrites the STATE/ERROR class-name tokens in a captured v1 QUIC trace using an
            R8/ProGuard mapping.txt. Every other event and field passes through unchanged.

            Via Gradle:
              ./gradlew :socket-quic-trace-tools:retraceQuicTrace \
                  -PtraceIn=captured.trace -Pmapping=mapping.txt -PtraceOut=captured.deobf.trace
            """.trimIndent(),
        )
        exitProcess(2)
    }
    val (traceInPath, mappingPath, traceOutPath) = args
    val deobfuscator = TraceDeobfuscator.fromMapping(File(mappingPath).readText())
    val deobfuscated = deobfuscator.deobfuscateAll(File(traceInPath).readLines())
    File(traceOutPath).writeText(deobfuscated.joinToString("\n", postfix = "\n"))
    println("Deobfuscated ${deobfuscated.size} trace lines → $traceOutPath")
}
