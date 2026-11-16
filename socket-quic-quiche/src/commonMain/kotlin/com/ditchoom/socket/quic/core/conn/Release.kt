@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.path.LocalAddr
import com.ditchoom.socket.quic.core.path.Made
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

    /**
     * Whoever entered for good after [asked] answered yes: frees the connection and every info its [paths] hold. A
     * sockaddr the connection made is freed with its info; the edge frees the ones it handed in.
     */
    fun free(paths: Paths) {
        val was = state.load()
        if (was !is Freeing.Requested || !state.compareAndSet(was, Freeing.Freed)) return
        q.api.connFree(q.handle)
        when (paths) {
            is Paths.Client -> paths.paths.made { local -> freeLocal(local) }
            is Paths.Server ->
                paths.paths.made { info, made ->
                    q.api.recvInfoFree(info)
                    when (made) {
                        is Made.Here -> made.sockAddr.free()
                        is Made.ByEdge -> Unit
                    }
                }
        }
        q.api.sendInfoFree(paths.sendInfo())
        was.then.run()
    }

    private fun freeLocal(local: LocalAddr) {
        q.api.recvInfoFree(local.recvInfo)
        when (val made = local.made) {
            is Made.Here -> made.sockAddr.free()
            is Made.ByEdge -> Unit
        }
    }
}
