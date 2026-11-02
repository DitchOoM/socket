@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Serve one accepted connection the way the harness echo server does: echo every stream chunk, echo
 * every datagram, push one greeting stream, and hold the connection until the peer goes away.
 *
 * Streams and datagrams are echoed by independent child jobs: a datagram-only client (e.g. the Apple
 * group path, issue #109) never opens a stream, and a stream client never sends datagrams. The
 * connection is held open until the PEER goes away (the datagram loop observes ConnectionClosed), so
 * a datagram client isn't cut off by the stream-accept timeout — and per-connection scopes mean one
 * lingering connection never blocks the server's accept loop.
 */
suspend fun QuicScope.echoConnection(quicOptions: QuicOptions) {
    val datagramJob =
        launch {
            try {
                while (true) {
                    when (val r = datagramChannel().receive()) {
                        is DatagramReadResult.Received -> {
                            val payload = r.datagram.payload
                            datagramChannel().send(payload) // reads zero-copy; we still own it
                            payload.freeIfNeeded()
                        }
                        is DatagramReadResult.Closed -> break
                    }
                }
            } catch (_: Exception) {
            }
        }
    // Server-initiated stream (issue #112): open one stream TOWARD the client
    // and write a fixed greeting, so clients can exercise acceptStream() against a
    // real peer-initiated stream (e.g. an HTTP/3 server's control/QPACK streams).
    // Independent of the echo path; clients that don't accept it simply ignore it.
    val pushJob =
        launch {
            try {
                val s = openStream()
                val greeting = "HELLO\n"
                val buf = BufferFactory.deterministic().allocate(greeting.length)
                buf.writeString(greeting, Charset.UTF8)
                buf.resetForRead()
                s.writeFully(buf, 10.seconds)
                buf.freeNativeMemory()
                s.close() // FIN, so the client sees the greeting then End
            } catch (_: Exception) {
            }
        }
    val streamJob =
        launch {
            try {
                // acceptStream has a timeout so a datagram-only / health-check
                // connection doesn't keep this job parked forever.
                val stream = withTimeout(3.seconds) { acceptStream() }
                try {
                    // The stream lives as long as the connection: only the peer's FIN/RESET or the
                    // connection's own end closes it. The read deadline is the connection's idle
                    // timeout, so a quiet stream is re-armed exactly as often as an unkept
                    // connection would have died — never sooner (#620).
                    while (true) {
                        val data =
                            try {
                                stream.read(quicOptions.idleTimeout)
                            } catch (e: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                                continue
                            }
                        when (data) {
                            is ReadResult.Data ->
                                try {
                                    stream.writeFully(data.buffer, 10.seconds)
                                } finally {
                                    // read transfers ownership; write is zero-copy and takes none — without this
                                    // free every echoed chunk leaks, and accumulated echo leaks were the #401
                                    // corruption's primer. writeFully because a QUIC write may be partial.
                                    data.buffer.freeIfNeeded()
                                }
                            ReadResult.End, ReadResult.Reset -> break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    stream.close()
                }
            } catch (_: Exception) {
                // No stream opened (datagram-only or health-check) — fine.
            }
        }
    datagramJob.join()
    streamJob.cancel()
    pushJob.cancel()
}
