package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.loop.LoopStart

/** A thread the platform refused fails the call on the caller's thread. */
internal fun LoopStart.raiseIfRefused() =
    when (this) {
        is LoopStart.Started -> Unit
        is LoopStart.Refused -> loopThreadRefused(thrown.throwable)
    }
