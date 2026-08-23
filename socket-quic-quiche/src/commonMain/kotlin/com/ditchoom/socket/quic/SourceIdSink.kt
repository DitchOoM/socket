package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer

/**
 * Where a [QuicheDriver] publishes **quiche's current set of source connection IDs**, so a server's
 * DCID→driver routing table can be a *projection* of quiche's own state rather than a ledger
 * reconstructed from replayed events.
 *
 * ## Why a set and not two events
 * The server's routing map is what decides whether a datagram reaches a connection at all, so a
 * disagreement between it and quiche's CID table is not cosmetic — it is #437 (a packet delivered to
 * a connection that no longer knows the id, answered with PROTOCOL_VIOLATION). That map used to be
 * fed by two independent event streams: an "issued" notification from `quiche_conn_new_scid` and a
 * "retired" notification drained from `quiche_conn_retired_scid_iter`, each queued across a
 * coroutine hop, applied in an order that had to be reasoned about, and each capable of leaving the
 * map permanently wrong if one were dropped, duplicated, or reordered. Nothing ever compared the
 * result against quiche.
 *
 * `quiche_conn_source_ids` is a plain, side-effect-free read of the live set, which makes a different
 * shape possible: the driver hands over **everything quiche currently recognises**, and the server
 * makes its routes equal that. The operation is idempotent, so a duplicate is free and a missed one
 * is repaired by the next projection instead of accumulating. There is no order to get right,
 * because there is only one operation.
 *
 * ## Ownership and threading
 * [replaceRoutes] is called on the driver's own coroutine — the only thread allowed to touch the
 * connection — and only when the set may have changed (a CID was issued, one was retired, or the
 * count disagrees with the last projection), so a steady-state connection pays one integer read per
 * wake and nothing else.
 *
 * [ids] is the driver's scratch buffer and is **valid only for the duration of the call**: an
 * implementation must snapshot what it needs before returning, exactly as the `onScidIssued`
 * callback this replaces had to.
 */
fun interface SourceIdSink {
    /**
     * Route exactly the [count] connection IDs in [ids] to this connection, and nothing else that
     * this projection previously placed.
     *
     * [ids] holds [count] slots of [RETIRED_SCID_SLOT_BYTES] bytes — each the id's length in its
     * first byte followed by that many id bytes — the layout [QuicheApi.connReadSourceIds] fills,
     * handed straight through with no intermediate copy.
     *
     * "and nothing else **that this projection previously placed**" is the precise contract: a
     * server also routes the client's original destination CID, chosen by the *client* during the
     * handshake and therefore never one of our source ids. quiche has no opinion about it, so a
     * projection must not remove it.
     */
    fun replaceRoutes(
        ids: PlatformBuffer,
        count: Int,
    )
}
