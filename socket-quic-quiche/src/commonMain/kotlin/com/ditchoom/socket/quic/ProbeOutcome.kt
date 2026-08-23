package com.ditchoom.socket.quic

/**
 * Result of a `quiche_conn_probe_path` call.
 *
 * Replaces the write-only `seqOut` out-param for the same reason [MigrateOutcome] did, and with more
 * at stake: probing a new path **consumes a spare destination connection ID**. quiche's
 * `create_path_on_client` takes `lowest_available_dcid_seq()` and links it to the path it just
 * created, so `available_dcids()` drops by one and stays down until that id is retired. When the
 * probe then fails validation, quiche's `on_failed_validation()` marks the path `Failed` and leaves
 * `active_dcid_seq` set — nothing gives the id back, and the path is not even evictable
 * (`Path::unused()` requires `active_dcid_seq.is_none()`).
 *
 * A driver that never reads the sequence number therefore has **no value to forget**: one
 * unanswered PATH_CHALLENGE permanently costs the connection one CID, and a handful of them park
 * migration for the rest of its life with `NoSpareConnectionId` (#447 — observed live against Google
 * on 2026-08-22). Returning the sequence as a value rather than writing it into scratch memory is
 * what makes that leak impossible to write: [QuicheDriver] cannot open a path entry without the id
 * that path holds.
 *
 * Each [QuicheApi] implementation decodes its platform-specific format into this hierarchy, so the
 * driver never deals with raw packed values.
 */
sealed interface ProbeOutcome {
    /**
     * quiche armed a PATH_CHALLENGE on the path; [dcidSeq] is the destination CID sequence number it
     * linked to that path, and which the driver owes a `quiche_conn_retire_dcid` the moment the path
     * stops being used — whether it validates, fails, or is abandoned on the RFC 9000 §8.2.4 timer.
     */
    class Probed(
        val dcidSeq: Long,
    ) : ProbeOutcome

    /**
     * quiche refused (`quiche_conn_probe_path` < 0). [code] is the raw quiche error.
     *
     * No CID is owed here: every failure inside `create_path_on_client` returns before
     * `link_dcid_to_path_id`, so a rejected probe consumes nothing.
     */
    class Rejected(
        val code: Int,
    ) : ProbeOutcome
}
