package com.ditchoom.socket.quic

/** Phase of an active connection migration, surfaced via [QuicScope.pathState]. */
enum class MigrationPhase {
    /** No migration has been requested. */
    None,

    /** A new path has been opened and a PATH_CHALLENGE is in flight. */
    Probing,

    /** The new path passed validation but the active path has not switched yet. */
    Validated,

    /** The connection's active path is now the migrated path. */
    Migrated,

    /** The most recent migration attempt failed; the connection stays on its previous path. */
    Failed,
}

/** Current migration/path state of a [QuicScope]. */
data class PathInfo(
    val phase: MigrationPhase = MigrationPhase.None,
    /** Local host of the active/target path, when known. */
    val localHost: String? = null,
    val localPort: Int = 0,
)

/** Result of a [QuicScope.migrate] call. */
sealed interface MigrationResult {
    /** The connection migrated to the requested local path. */
    data class Succeeded(
        val localHost: String?,
        val localPort: Int,
    ) : MigrationResult

    /** Migration was attempted but did not complete; [reason] explains why. */
    data class Failed(
        val reason: String,
    ) : MigrationResult

    /** This platform/connection does not support active migration (Apple, JS, server-accepted connections). */
    data object Unsupported : MigrationResult
}
