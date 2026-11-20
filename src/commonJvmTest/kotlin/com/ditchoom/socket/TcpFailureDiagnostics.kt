package com.ditchoom.socket

import com.ditchoom.socket.nio.util.SelectorLoopGroup
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Prints, only when a test of the class that applies it fails, what this JVM looked like at that moment: the
 * tests that ran just before (a leaked socket or loop from one of them is the usual way a failure appears only
 * in a full run), which client implementation was selected, the shared selector loops' parked and registered
 * counts, and the live threads by kind and state. A passing test costs one queue append.
 */
class TcpFailureDiagnostics : TestWatcher() {
    override fun starting(description: Description) {
        recent.add(description.displayName)
        while (recent.size > RECENT) recent.poll()
    }

    override fun failed(
        e: Throwable,
        description: Description,
    ) {
        val loops = SelectorLoopGroup.shared.running()
        val threads =
            Thread
                .getAllStackTraces()
                .keys
                .groupingBy { kindOf(it.name) + " " + it.state }
                .eachCount()
                .toSortedMap()
        System.err.println(
            buildString {
                appendLine(
                    "TCP FAILURE DIAGNOSTICS for ${description.displayName}: ${e::class.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}",
                )
                appendLine("  tests run just before: ${recent.toList().dropLast(1).joinToString(" | ")}")
                appendLine("  useAsyncChannels=$useAsyncChannels useNioBlocking=$useNioBlocking")
                appendLine(
                    "  selector loops: ${loops.size} running, parked channels ${loops.sumOf { it.parkedChannelCount }}, " +
                        "registered keys ${loops.sumOf { it.registeredKeyCount }}",
                )
                append("  threads: $threads")
            },
        )
    }

    private fun kindOf(name: String) =
        when {
            name.startsWith("socket-nio-loop") -> "socket-nio-loop"
            name.startsWith("DefaultDispatcher-worker") -> "DefaultDispatcher-worker"
            name.startsWith("InnocuousThread") -> "InnocuousThread(JDK NIO2 pool)"
            name.startsWith("Thread-") -> "Thread-N"
            else -> name.replace(Regex("[-# ]?\\d+$"), "")
        }

    private companion object {
        const val RECENT = 6
        val recent = ConcurrentLinkedQueue<String>()
    }
}
