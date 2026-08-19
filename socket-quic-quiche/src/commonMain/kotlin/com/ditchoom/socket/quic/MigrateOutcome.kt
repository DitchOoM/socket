package com.ditchoom.socket.quic

/**
 * Result of a `quiche_conn_migrate` call.
 *
 * Replaces the write-only `seqOut` out-param convention: the driver must retire the
 * previous DCID on the NEXT migration, so the sequence number now issued has to come back
 * as a value on the success path — and a raw out-param native-memory read can't be modeled
 * by pure-Kotlin test doubles. Each [QuicheApi] implementation decodes its platform-specific
 * format into this hierarchy, so the driver never deals with raw packed values.
 */
sealed interface MigrateOutcome {
    /** quiche accepted the switch; [dcidSeq] is the DCID sequence number now active on the new path. */
    class Migrated(
        val dcidSeq: Long,
    ) : MigrateOutcome

    /** quiche refused (`quiche_conn_migrate` < 0). [code] is the raw quiche error. */
    class Rejected(
        val code: Int,
    ) : MigrateOutcome
}
