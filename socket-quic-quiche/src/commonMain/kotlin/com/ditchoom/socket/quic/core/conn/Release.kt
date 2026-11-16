@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.path.SinglePath
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private sealed interface Freeing {
    data object Live : Freeing

    class Requested(
        val then: Runnable,
    ) : Freeing

    data object Freed : Freeing
}

/** Whether a release was asked for and the connection is not yet freed. */
internal enum class ReleaseAsk { No, Yes }

/** Frees quiche's memory once nobody is inside: the only file that frees a connection or its infos. */
@Published
internal class Release(
    private val q: QuicheConnRef,
) {
    private val state = AtomicReference<Freeing>(Freeing.Live)

    /** Any thread: free the connection when nobody is inside, then run [then]. */
    fun request(then: Runnable) {
        state.compareAndSet(Freeing.Live, Freeing.Requested(then))
    }

    fun asked(): ReleaseAsk = if (state.load() is Freeing.Requested) ReleaseAsk.Yes else ReleaseAsk.No

    /** Whoever entered for good after [asked] answered yes. */
    fun free(path: SinglePath) {
        val was = state.load()
        if (was !is Freeing.Requested || !state.compareAndSet(was, Freeing.Freed)) return
        q.api.connFree(q.handle)
        q.api.recvInfoFree(path.recvInfo)
        q.api.sendInfoFree(path.sendInfo)
        was.then.run()
    }
}
