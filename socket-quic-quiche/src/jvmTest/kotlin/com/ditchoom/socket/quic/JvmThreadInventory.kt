package com.ditchoom.socket.quic

/**
 * What the JVM's threads were doing at the moment a loopback exchange failed — the closest thing a
 * test process has to the gdb dump CI's hang watchdog takes, and the one measurement that decides a
 * *hang* rather than a failure: which thread is parked, on what, and whether the driver loop that
 * should have delivered the bytes is alive at all.
 *
 * Two facts are counted up front because they have each explained a real stall on their own:
 *
 * - `inSelect`: `Dispatchers.IO` admits at most `kotlinx.coroutines.io.parallelism` (64 by default)
 *   *concurrent* tasks, and `NioDatagramChannelCore.receive` parks `Selector.select()` inside
 *   `runInterruptible(Dispatchers.IO)` — one admission held for as long as the receive is parked,
 *   which for a QUIC socket is "until a datagram arrives". A receive queued behind that limit never
 *   reads its socket at all (deaf from birth, to its own client and to a probe alike).
 * - `parkedInQuiche`: a thread inside `quiche_*` / the JNI or FFM shim is a native call that has not
 *   returned — the shape of a lock held across FFI, or a native crash being unwound.
 *
 * Then every thread with a `com.ditchoom`, `sun.nio.ch` or quiche frame, six frames deep, so the
 * report names the coroutine machinery's *current* frame rather than the test's await.
 */
internal fun jvmThreadInventory(): String {
    val stacks = Thread.getAllStackTraces()
    val inSelect =
        stacks.count { (_, frames) ->
            frames.any { it.className.startsWith("sun.nio.ch") && it.methodName.contains("select", ignoreCase = true) }
        }
    val parkedInQuiche =
        stacks.count { (_, frames) ->
            frames.take(3).any { it.className.contains("quiche", ignoreCase = true) && it.isNativeMethod }
        }
    val interesting =
        stacks.entries
            .filter { (_, frames) ->
                frames.any {
                    it.className.startsWith("sun.nio.ch") ||
                        it.className.startsWith("com.ditchoom") ||
                        it.className.contains("quiche", ignoreCase = true)
                }
            }.sortedBy { (t, _) -> t.name }
            .joinToString("\n") { (t, frames) ->
                "      [${t.name} ${t.state}] " + frames.take(6).joinToString(" <- ") { "${it.className}.${it.methodName}" }
            }
    return "total=${stacks.size} inSelect=$inSelect parkedInQuiche=$parkedInQuiche ioParallelism=" +
        (System.getProperty("kotlinx.coroutines.io.parallelism") ?: "<default 64>") +
        "\n$interesting"
}
