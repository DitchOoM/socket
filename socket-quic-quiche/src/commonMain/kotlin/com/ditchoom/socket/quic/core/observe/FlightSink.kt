package com.ditchoom.socket.quic.core.observe

/** Where a dumped recording goes: a file, a log, a trace artifact. Bytes arrive in order; a dump is rare. */
internal interface FlightSink {
    fun put(byte: Byte)
}

/** A recording being read back. */
internal interface FlightSource {
    fun hasMore(): Boolean

    fun next(): Byte
}
