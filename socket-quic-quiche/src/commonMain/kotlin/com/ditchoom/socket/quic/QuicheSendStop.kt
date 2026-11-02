package com.ditchoom.socket.quic

/**
 * Why `quiche_conn_send` produced no datagram — decoded once, where the driver calls it, so the policy
 * for each cause is an exhaustive `when` instead of a sign test.
 *
 * A positive return is a datagram's length and never reaches this type: decoding only the non-positive
 * returns keeps the per-datagram send path free of an allocation.
 *
 * ## What quiche 0.30 `send()` can return, and what each means here
 * `quiche_conn_send` → `Connection::send` → `send_on_path(out, None, None)`, with errors mapped by
 * `Error::to_c`. Read from `quiche/src/lib.rs`:
 *
 * - **`Done` (-1)** — the normal end of a drain: closed, draining, Initial secrets not derived yet, or
 *   `done == 0` after the coalescing loop (which also absorbs `send_single`'s own `Done` *and*
 *   `BufferTooShort`). → [NothingToSend].
 * - **`BufferTooShort` (-2)** — returned by `send_on_path` itself only when `out.is_empty()`; a buffer
 *   merely too small for the next packet is folded into `Done` above. Transient by construction: it
 *   describes the buffer this call offered, not the connection, so the next flush retries with
 *   whatever window it computes. → [BufferTooShort].
 * - **Anything else** — `do_handshake` errors (`TlsFail`, `CryptoFail`, … when no local error was
 *   recorded yet), `get_send_path_id`/`paths.get_mut` → `InvalidState` (no usable send path), and
 *   every `send_single` error other than `Done`/`BufferTooShort`: `write_pkt_type`/`to_epoch`,
 *   `ids.mark_retire_dcid_seq` (`InvalidState`), a path with no DCID (`OutOfIdentifiers` /
 *   `InvalidState`), and packet encryption. (`octets` overruns are `BufferTooShort` and fold into
 *   `Done`.) None of these is cured by calling again: quiche returns the same error from
 *   the same state, and treating it as "nothing to send" is what left connections silent — no ACKs —
 *   until the idle timeout (the two quiche defects patched in #764/#765 both surfaced as `-6`).
 *   → [Rejected].
 *
 * Zero is not a quiche return (`send_on_path` turns `done == 0` into `Done`), but zero bytes written is
 * unambiguously "nothing to send", so a test double returning it means exactly that.
 */
internal sealed interface QuicheSendStop {
    /** `QUICHE_ERR_DONE`: quiche has nothing more to send right now. Stop this flush. */
    data object NothingToSend : QuicheSendStop

    /**
     * `QUICHE_ERR_BUFFER_TOO_SHORT`: the buffer offered could not take a packet. Stop this flush; the
     * next one offers its own window and quiche tries again.
     */
    data object BufferTooShort : QuicheSendStop

    /** quiche refused to produce a packet with [quicheCode], a state no later call cures. */
    data class Rejected(
        val quicheCode: Int,
    ) : QuicheSendStop

    companion object {
        /** `QUICHE_ERR_BUFFER_TOO_SHORT` (quiche.h). */
        const val QUICHE_ERR_BUFFER_TOO_SHORT = -2

        /** Decode a non-positive `quiche_conn_send` return. */
        fun decode(raw: Int): QuicheSendStop {
            require(raw <= 0) { "a positive return ($raw) is a datagram length, not a stop" }
            return when (raw) {
                0, QuicheDriver.QUICHE_ERR_DONE -> NothingToSend
                QUICHE_ERR_BUFFER_TOO_SHORT -> BufferTooShort
                else -> Rejected(raw)
            }
        }
    }
}

/**
 * Trace marker for a [QuicheSendStop.Rejected]: recorded the moment the driver sees it, before it
 * closes the connection, so a capture shows the raw code even when the close that follows ends up
 * reporting a reason quiche recorded itself.
 */
internal class QuicheSendRejected(
    quicheCode: Int,
) : RuntimeException("quiche_conn_send returned $quicheCode")
