@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * Where this connection's primary path came from, and therefore whether it can ever hold another.
 *
 * A sealed answer rather than a nullable factory: "no factory" and "a factory that is null" read the
 * same at a call site, and the migration wiring is the one place in this driver where getting that
 * wrong is silent — a connection with no factory simply never migrates and nothing asks why, which is
 * how Apple shipped without migration (see [MigrationCapability]).
 */
internal sealed interface ClientPathOrigin {
    /** The connection owns its socket. [factory] opened the primary path and opens every later one. */
    class Owned(
        val factory: UdpSocketChannelFactory,
    ) : ClientPathOrigin

    /**
     * The port's owner holds the socket, so this connection cannot open a second local path — see
     * [QuicClientBinding.Shared] for why that is a property of sharing and not a gap to be filled.
     */
    data object SharedPort : ClientPathOrigin
}

/** The primary path, ready to hand to the driver, plus what it implies about migration. */
internal class ClientPath(
    val udpChannel: UdpChannel,
    val localAddress: SocketAddress,
    val origin: ClientPathOrigin,
)

/**
 * Resolve a [QuicClientBinding] into the connection's primary path. Common to every platform: the
 * platforms differ only in the [UdpChannelFactory] they build (each `UdpSocket.connect` actual
 * disagrees about whether a local endpoint can be bound — see [LocalEndpointSupport]), which is why
 * [ownFactory] is supplied by the caller and everything after it is not.
 *
 * [ownFactory] is a lambda rather than a value so the shared branch never constructs one: a factory
 * built and then not used is a socket-opening capability sitting next to a socket it must not open.
 */
internal suspend fun QuicClientBinding.openClientPath(
    peer: SocketAddress,
    ownFactory: () -> UdpSocketChannelFactory,
): ClientPath =
    when (this) {
        QuicClientBinding.OwnSocket -> {
            val factory = ownFactory()
            val channel = factory.openPrimaryChannel()
            ClientPath(
                udpChannel = DatagramChannelUdpChannel(channel),
                localAddress =
                    channel.localAddress.orNull()
                        ?: error("connected UDP channel has no local address"),
                origin = ClientPathOrigin.Owned(factory),
            )
        }

        is QuicClientBinding.Shared ->
            ClientPath(
                udpChannel = ClientSharedPortUdpChannel(channel, peer),
                // The owner bound this socket, so its local address is the one quiche must record as
                // `recv_info.to`. Unlike the owned case there is nothing to fall back to: a channel
                // that cannot say where it is bound cannot be shared, because the connection would
                // have no honest answer for the address its peer is talking to.
                localAddress = channel.localAddress,
                origin = ClientPathOrigin.SharedPort,
            )
    }

/**
 * The transport options this binding actually runs with — the client mirror of
 * [QuicPortBinding.transportOptionsFor].
 *
 * On a shared port GREASE is forced off: RFC 9443 §3 requires an endpoint that demultiplexes QUIC not
 * to send the `grease_quic_bit` transport parameter (RFC 9287), because a peer thereby permitted to
 * grease the fixed bit sends short-header packets whose first byte falls in the DTLS and STUN ranges
 * — unclassifiable, and so undeliverable to any stack on the port. Forced rather than validated,
 * because a requirement only a doc comment enforces is one a caller discovers through a silent,
 * intermittent outage.
 */
internal fun QuicClientBinding.transportOptionsFor(options: QuicOptions): QuicOptions =
    when (this) {
        QuicClientBinding.OwnSocket -> options
        is QuicClientBinding.Shared -> if (options.enableGrease) options.copy(enableGrease = false) else options
    }

/**
 * The migration capability this binding permits, given the caller's [MigrationPolicy].
 *
 * [ClientPathOrigin.SharedPort] answers [MigrationCapability.BackendCannotMigrate] — the same answer
 * a backend with no path factory gives, and for the same reason: there is no second local path to be
 * had. The policy is not consulted, because a caller who asked for migration on a socket they do not
 * own asked for something the arrangement cannot provide, and reporting `PolicyForbids` would name
 * the wrong cause.
 */
internal fun ClientPathOrigin.migrationCapability(
    policy: MigrationPolicy,
    supported: (UdpSocketChannelFactory) -> MigrationCapability.Supported,
): MigrationCapability =
    when (this) {
        is ClientPathOrigin.Owned -> clientMigrationCapability(policy) { supported(factory) }
        ClientPathOrigin.SharedPort -> MigrationCapability.BackendCannotMigrate
    }
