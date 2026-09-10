@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

/**
 * Where a QUIC **client** connection's local endpoint comes from — its own socket, or one somebody
 * else owns and demultiplexes to it (RFC 9443 port sharing, the client half of [QuicPortBinding]).
 *
 * ## Why this is not [QuicPortBinding]
 * The server's binding carries `Own(port, host)`, and a client has nothing to do with either: it
 * binds the source address its route dictates on an ephemeral port, which is what connection
 * migration later rebinds against (#519). Reusing that type would let `Own(5000, "10.0.0.1")`
 * compile on the connect path and be silently ignored — a capability advertised and not honoured,
 * which is the defect shape of #579. So the client's binding says only what a client can answer.
 */
public sealed interface QuicClientBinding {
    /**
     * The connection opens and owns its own UDP socket — the ordinary case, and the only one that can
     * migrate. Unchanged from every client connection this library has ever made.
     */
    public data object OwnSocket : QuicClientBinding

    /**
     * The connection reads and writes an already-bound channel it does **not** own, so QUIC rides the
     * same port, the same 5-tuple, the same NAT binding and the same gathered ICE candidate set as
     * whatever else is on it. `close()` on the connection ends only this branch; the port keeps
     * serving its owner.
     *
     * Two obligations come with sharing, both discharged here rather than left to a doc comment:
     * GREASE is disabled (RFC 9443 §3 forbids `grease_quic_bit` on a demultiplexed port, because a
     * peer thereby permitted to grease the fixed bit emits short-header packets whose first byte
     * lands in the DTLS and STUN ranges), and the connection reports
     * [MigrationPolicy]-independent inability to migrate — see below.
     *
     * ## ⚠️ A shared-port client cannot migrate, and that is a property of sharing
     * RFC 9000 §9 migration means moving to a new 4-tuple, and the local half of one is
     * (address, port). Changing the port means a second socket — which is outside the
     * demultiplexer and outside the candidate set, so it has left the arrangement that sharing
     * exists to create. The socket's owner is therefore the only thing that can change this
     * connection's path, and the connection reports
     * [MigrationResult.Unmoved.Impossible.BackendCannotMigrate] rather than pretending otherwise.
     *
     * This is not a limitation that a callback would lift. `draft-seemann-quic-nat-traversal` — QUIC
     * doing its own ICE-style hole punching — needs `ADD_ADDRESS`/`PUNCH_ME_NOW` extension frames
     * inside quiche, which this library's vendored quiche does not implement. When it does, this
     * type gains a case; a sealed interface makes that a compile error at every site that must
     * reconsider, which is exactly why it is one.
     *
     * @param channel the demultiplexed QUIC branch — e.g. `MultiplexedUdpSocket.quic` from
     *   `:socket-udp`'s `AddressedDatagramChannel.demultiplex()`. It must deliver only
     *   QUIC-classified datagrams; classifying them is the demultiplexer's job, not this one's.
     */
    public class Shared(
        public val channel: AddressedDatagramChannel,
    ) : QuicClientBinding
}
