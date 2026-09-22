package com.ditchoom.socket.testkit.echo

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A peer that echoes the Nth write [echoAfter] (N) after it is written, on the caller's [clock]. The
 * stream is ordered, so an echo never becomes readable before the one written ahead of it, and a
 * read returns everything readable at once, as a real stream coalesces.
 */
internal class ScriptedEchoStream(
    private val clock: () -> Duration,
    private val echoAfter: (write: Int) -> Duration,
) : EchoStream {
    private class InFlight(
        val readableAt: Duration,
        val text: String,
    )

    private val inFlight = ArrayDeque<InFlight>()
    private var writes = 0

    override suspend fun write(
        payload: String,
        deadline: Duration,
    ): EchoWrite {
        writes++
        val behind = inFlight.lastOrNull()?.readableAt ?: Duration.ZERO
        inFlight.addLast(InFlight(maxOf(clock() + echoAfter(writes), behind), payload))
        return EchoWrite.Written
    }

    override suspend fun read(deadline: Duration): StreamReply {
        val giveUpAt = clock() + deadline
        while (true) {
            val readable = StringBuilder()
            while (inFlight.isNotEmpty() && inFlight.first().readableAt <= clock()) readable.append(inFlight.removeFirst().text)
            if (readable.isNotEmpty()) return StreamReply.Echoed(readable.toString())
            val next = inFlight.firstOrNull()?.readableAt
            if (next == null || next > giveUpAt) {
                delay(giveUpAt - clock())
                return StreamReply.StillOwed
            }
            delay(next - clock())
        }
    }
}
