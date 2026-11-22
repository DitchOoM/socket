package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.conn.ArmedDeadline
import com.ditchoom.socket.quic.core.conn.LingerView
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.Wire

/** Each connection [demux] still routes: its life, its linger's wire and end against now, and the deadline it holds. */
internal fun routedEvidence(demux: Demux): String = demux.routedViews().joinToString("; ", "[", "]") { describe(it) }

internal fun describe(view: LingerView): String {
    val life =
        when (val at = view.life) {
            is Life.Running -> "Running"
            is Life.Flushing -> "Flushing"
            is Life.Handed -> "Handed"
            is Life.Lingering -> "Lingering(${at.end::class.simpleName})"
            is Life.Ended -> "Ended(${at.end::class.simpleName})"
        }
    val wire =
        when (val w = view.wire) {
            is Wire.Answering -> "Answering, ends in ${(w.until - view.now) / MS} ms"
            is Wire.Silent -> "Silent, ends in ${(w.until - view.now) / MS} ms"
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Done -> w::class.simpleName
        }
    val deadline =
        when (val d = view.deadline) {
            is ArmedDeadline.Unarmed -> "no deadline armed"
            is ArmedDeadline.Due -> "deadline due in ${d.after.inWholeMilliseconds} ms"
        }
    return "$life, $wire, $deadline"
}

private const val MS = 1_000_000L
