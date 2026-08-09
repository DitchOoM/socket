@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

/**
 * Where a QUIC listener's datagrams come from: a UDP port it binds itself, or one somebody else owns
 * and shares with it.
 *
 * The second case is what lets QUIC — and therefore HTTP/3 and WebTransport — coexist with the
 * WebRTC family (ICE/STUN, DTLS, SRTP, TURN) on a single port such as 443. A socket can have only
 * one reader, so the sharing arrangement is that a demultiplexer owns the socket, classifies each
 * datagram by RFC 9443, and hands QUIC a [Shared] branch carrying only QUIC packets. See
 * `AddressedDatagramChannel.demultiplex()` in `:socket-udp`.
 *
 * A sealed type rather than a nullable channel: "bind port 443" and "read this channel" are
 * different instructions, and a nullable would let a caller pass both, or neither, and force every
 * backend to invent a precedence rule.
 */
sealed interface QuicPortBinding {
    /**
     * The listener binds its own UDP socket and is its sole user — the ordinary case.
     *
     * [port] 0 asks the OS for an ephemeral port; [host] null binds every interface.
     */
    data class Own(
        val port: Int = 0,
        val host: String? = null,
    ) : QuicPortBinding

    /**
     * The listener reads and writes an already-bound channel it does NOT own. Everything about the
     * socket — its address, its lifetime — belongs to whoever bound it, so `QuicServer.close()`
     * closes only this branch, leaving the port serving whatever else is on it.
     *
     * Two obligations come with sharing, both discharged by the backend rather than left to a doc
     * comment: [channel] must deliver only QUIC-classified datagrams (a demultiplexer's job), and
     * GREASE is disabled on the connection because RFC 9443 §3 forbids sending the
     * `grease_quic_bit` transport parameter here — a peer allowed to grease the fixed bit emits
     * short-header packets whose first byte lands in the DTLS and STUN ranges, and no demultiplexer
     * can classify those.
     */
    class Shared(
        val channel: AddressedDatagramChannel,
    ) : QuicPortBinding
}
