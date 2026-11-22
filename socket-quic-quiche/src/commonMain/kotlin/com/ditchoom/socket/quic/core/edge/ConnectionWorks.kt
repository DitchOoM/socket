package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

/** What an on-demand dump of a connection's flight recording did. */
internal enum class FlightDump {
    /** Every record the connection still holds was written. */
    Written,

    /** The connection had ended; with `QuicOptions.trace` set its recording already went to the trace. */
    ConnectionEnded,
}

/** Lets a client's held first flight leave. */
internal class ReleaseFlight : ConnWork() {
    override fun run(inside: Inside) = inside.releaseFirstFlight()

    override fun gone(end: ConnEnd) = Unit
}

/** Writes the flight recording inside the connection. */
internal class DumpWork(
    private val sink: TraceSink,
    private val cont: CancellableContinuation<FlightDump>,
) : ConnWork() {
    override fun run(inside: Inside) {
        inside.dump(sink)
        cont.resume(FlightDump.Written)
    }

    override fun gone(end: ConnEnd) = cont.resume(FlightDump.ConnectionEnded)
}
