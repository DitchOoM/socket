package com.ditchoom.socket

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode

// DIAGNOSTIC ONLY — never merge. Tags every counted open/close with the owner's identity so a test log
// shows which [ RUN ] block opened a socket that was never closed.
@OptIn(ExperimentalNativeApi::class)
internal object IoUringDiag {
    fun opened(
        kind: String,
        owner: Any,
    ) = println("IOURING-DIAG open ${owner.identityHashCode()} $kind")

    fun closed(
        kind: String,
        owner: Any,
    ) = println("IOURING-DIAG close ${owner.identityHashCode()} $kind")
}
