package com.ditchoom.socket.testkit.echo

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSinkStalledException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Writes all of [buffer] within [deadline], and says how far it got when the deadline wins.
 *
 * A stream write may be partial — a QUIC stream takes only what its flow-control credit allows — so
 * one bare `write` whose count is dropped loses the record's tail and leaves the stream misaligned for
 * good. `writeFully` resumes, but its timeout cannot tell "nothing went" from "half went", and an echo
 * probe must: the first leaves the stream intact, the second ends the session ([EchoWrite.Torn]).
 */
public suspend fun ByteSink.writeEcho(
    buffer: ReadBuffer,
    deadline: Duration,
): EchoWrite {
    val total = buffer.remaining()
    var accepted = 0
    try {
        withTimeout(deadline) {
            while (buffer.remaining() > 0) {
                val before = buffer.position()
                val written = write(buffer, deadline).count
                if (written <= 0) throw ByteSinkStalledException(accepted = written, pending = buffer.remaining())
                accepted += written
                val resumeAt = before + written
                if (buffer.position() != resumeAt) buffer.position(resumeAt)
            }
        }
    } catch (e: TimeoutCancellationException) {
        // Only this deadline's own cancellation, which wears the same type, is answered here.
        currentCoroutineContext().ensureActive()
        return if (accepted == 0) EchoWrite.TimedOut else EchoWrite.Torn(accepted, total - accepted)
    }
    return EchoWrite.Written
}
