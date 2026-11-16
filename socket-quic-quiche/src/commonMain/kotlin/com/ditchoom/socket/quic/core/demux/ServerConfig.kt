package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.LoopMemory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether the server makes a client prove it owns its address before the server spends state on it. */
internal sealed interface AddressValidation {
    /** Accepts the first Initial it sees. */
    data object Off : AddressValidation

    /** Answers a first Initial with a Retry carrying a stateless token that is good for [lifetime]. */
    class Retry(
        val lifetime: Duration = 10.seconds,
    ) : AddressValidation
}

/**
 * How many handshakes the server runs at once. Every Initial that would start one waits in a backlog that is admitted
 * in chunks between socket reads, at most [perPass] in one pass; one over [inProgress], or one that finds the backlog
 * full, is dropped and its client retransmits.
 */
internal class AdmissionBounds(
    /** Handshakes started in one pass of the loop. */
    val perPass: Int,
    /** Handshakes begun and not yet complete, in total. */
    val inProgress: Int,
    /**
     * Initials the server holds, so a small kernel receive buffer never has to. Allocated up front at one 1,500-byte
     * slot each: the default is 6 MiB of native memory per server.
     */
    val backlog: Int = DEFAULT_BACKLOG,
) {
    companion object {
        const val DEFAULT_BACKLOG = 4_096
        val Default = AdmissionBounds(perPass = 64, inProgress = 1024)
    }
}

/** What a server is configured with besides its certificate and transport options. */
internal class ServerConfig(
    val addressValidation: AddressValidation = AddressValidation.Off,
    val admission: AdmissionBounds = AdmissionBounds.Default,
    /** Each accepted connection's flight recording: small by default, a server holds many. */
    val flight: FlightSize = FlightSize(records = 256),
    /** The payload arena every connection's flight recording on the server's loop shares. */
    val flightArenaBytes: Int = LoopMemory.ARENA_BYTES,
)
