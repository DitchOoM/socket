package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Which factory a migration opens its socket through: the connection's own, which binds wherever the
 * platform's default route points, or one whose sockets are pinned to a standby link.
 */
internal sealed interface PathVia {
    /** The connection's own factory. What every public [QuicScope.migrate] call uses. */
    data object DefaultRoute : PathVia

    /** [factory] opens sockets pinned to a standby link, whatever the default route says. */
    class Standby(
        val factory: UdpChannelFactory,
    ) : PathVia
}

/**
 * A link beside the default route that a connection can move onto without waiting for the OS to
 * declare the default link lost — see [StandbyLink].
 */
internal sealed interface StandbyPath {
    /** Nothing is attached beside the default route: the policy asks for none, the platform has none, or it is still attaching. */
    data object Unavailable : StandbyPath

    /** The standby link [id] is attached and routable; [migrate] moves the connection onto it. */
    class Ready(
        val id: NetworkId,
        private val moveOnto: suspend () -> MigrationResult,
    ) : StandbyPath {
        suspend fun migrate(): MigrationResult = moveOnto()

        override fun toString(): String = "Ready($id)"
    }
}

/** One connection's view of its standby link. [current] is re-read at the moment of use; [changes] wakes a waiting retry. */
internal interface StandbyPaths {
    val current: StandbyPath
    val changes: Flow<StandbyPath>

    /** No standby link, ever: the policy asked for none, or the platform has no radio to hold. */
    object None : StandbyPaths {
        override val current: StandbyPath get() = StandbyPath.Unavailable
        override val changes: Flow<StandbyPath> = flowOf(StandbyPath.Unavailable)
    }
}

/**
 * Move [this] driver's connection to a fresh local endpoint opened through [via], suspending until the
 * new path has validated and become active or the attempt has failed. The one place a [PathVia] other
 * than the default route reaches the driver.
 */
internal suspend fun QuicheDriver.migrateVia(via: PathVia): MigrationResult =
    try {
        val deferred = kotlinx.coroutines.CompletableDeferred<MigrationResult>()
        commands.send(QuicheCmd.Migrate(MigrationTarget.FreshLocalEndpoint, deferred, via))
        deferred.await()
    } catch (_: ClosedSendChannelException) {
        MigrationResult.Unmoved.Impossible.ConnectionClosed
    }
