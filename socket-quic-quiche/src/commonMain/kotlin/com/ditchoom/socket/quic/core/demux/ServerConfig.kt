package com.ditchoom.socket.quic.core.demux

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether the server makes a client prove it owns its address before the server spends state on it. */
internal sealed interface AddressValidation {
    /** Accepts the first Initial it sees. */
    object Off : AddressValidation

    /** Answers a first Initial with a Retry carrying a stateless token that is good for [lifetime]. */
    class Retry(
        val lifetime: Duration = 10.seconds,
    ) : AddressValidation
}

/** How many handshakes the server runs at once: an Initial over either bound is dropped, and the client retransmits. */
internal class Admission(
    /** Handshakes started in one pass of the loop. */
    val perPass: Int,
    /** Handshakes begun and not yet complete, in total. */
    val inProgress: Int,
) {
    companion object {
        val Default = Admission(perPass = 64, inProgress = 1024)
    }
}

/** What a server is configured with besides its certificate and transport options. */
internal class ServerConfig(
    val addressValidation: AddressValidation = AddressValidation.Off,
    val admission: Admission = Admission.Default,
)

/** What a server did with the datagrams that reached it. Loop thread writes; any thread may read. */
internal class ServerCounts {
    /** Datagrams handed to a connection. Written for every datagram, so read it once the server is quiet. */
    var routed = 0L

    @kotlin.concurrent.Volatile
    var accepted = 0L

    /** Datagrams that named no connection and were not an Initial able to start one. */
    @kotlin.concurrent.Volatile
    var droppedUnknown = 0L

    /** Initials dropped because the handshake bounds were reached. */
    @kotlin.concurrent.Volatile
    var droppedAdmission = 0L

    /** Initials dropped for carrying a token that is malformed, forged or expired. */
    @kotlin.concurrent.Volatile
    var droppedToken = 0L

    /** Initials smaller than 1200 bytes, which RFC 9000 section 14.1 says to discard. */
    @kotlin.concurrent.Volatile
    var droppedRunt = 0L

    /** Retry packets and Version Negotiation packets that could not be queued or written. */
    @kotlin.concurrent.Volatile
    var droppedReplies = 0L

    @kotlin.concurrent.Volatile
    var retriesSent = 0L

    @kotlin.concurrent.Volatile
    var versionNegotiationsSent = 0L

    /** `recv_info` structures built: one per accepted connection, never per datagram. */
    @kotlin.concurrent.Volatile
    var recvInfosBuilt = 0L
}
