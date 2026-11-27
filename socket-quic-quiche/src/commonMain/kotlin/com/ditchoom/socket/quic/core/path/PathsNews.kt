@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: what a client tells its edge.
@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.time.Span
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmInline

/** Whether a client judges its active path's silence: only one that may move on its own does. */
internal enum class Silence { Off, On }

/** What the data plane says of the active path. */
internal sealed interface PathNews {
    /** Something arrived since the path last went unanswered, or nothing is outstanding to be answered. */
    data object Answering : PathNews

    /** The path met [SilenceThreshold]: its PTO expiries, or the ack-eliciting data handed to it, went unanswered. */
    data object Silent : PathNews
}

/**
 * How the connection wakes its network feed: one wake pending at most, sent without waiting and without a lock. The
 * feed reads the cells it is woken for, so a wake that finds one already pending loses nothing.
 */
internal class FeedWake {
    private val channel = Channel<Unit>(1)

    /** What the feed receives from; it is woken when one is there. */
    val pending: ReceiveChannel<Unit> get() = channel

    fun poke() {
        channel.trySend(Unit)
    }
}

/**
 * The path's news and how many times it was declared silent so far, in one Long so telling allocates nothing: a silence
 * the feed missed still counts.
 */
@JvmInline
internal value class Told(
    val bits: Long,
) {
    val news: PathNews get() = if ((bits and SILENT_BIT) != 0L) PathNews.Silent else PathNews.Answering

    val silences: Long get() = bits ushr 1

    /** This, told [now]. */
    fun then(now: PathNews): Told =
        when (now) {
            is PathNews.Silent -> Told(((silences + 1) shl 1) or SILENT_BIT)
            is PathNews.Answering -> Told(silences shl 1)
        }

    private companion object {
        const val SILENT_BIT = 1L
    }
}

/**
 * The edge's reading of a connection's [PathNews]: written inside the connection on each change, read on any thread,
 * and its feed woken ([wake]) to ask for a rebind each time the path is declared silent ([Told.silences]).
 */
@Published
internal class PathNewsCell(
    val wake: FeedWake = FeedWake(),
) {
    private val told = AtomicLong(0L)

    fun read(): PathNews = told().news

    fun told(): Told = Told(told.load())

    fun tell(now: PathNews) {
        told.store(told().then(now).bits)
        wake.poke()
    }
}

/** Where a client's socket is after its moves: where it opened, or the carrying endpoint once its [settles]th move ended. */
internal sealed interface Landed {
    data object Opened : Landed

    class At(
        val local: PathKey,
        val settles: Long,
    ) : Landed
}

/**
 * Where a client's socket is, as the connection, the one that decides, tells it each time a move it asked for ended,
 * landed or not: written inside the connection, read by the network feed, which holds no guess of its own.
 */
@Published
internal class HomeCell(
    private val wake: FeedWake = FeedWake(),
) {
    private val landed = AtomicReference<Landed>(Landed.Opened)

    fun read(): Landed = landed.load()

    /** A move ended: the socket that carries leaves from [local]. */
    fun settled(local: PathKey) {
        val settles =
            when (val was = landed.load()) {
                is Landed.Opened -> 1L
                is Landed.At -> was.settles + 1
            }
        landed.store(Landed.At(local, settles))
        wake.poke()
    }
}

/**
 * What a client's silence watch is built with: whether it judges its path ([watch]), how far its awaiting-peer probe
 * backs off ([probeCap], the idle timeout), where it tells the edge ([news]), and where the edge learns where the
 * socket is once a move ended ([home]).
 */
internal class SilenceRule(
    val watch: Silence,
    val probeCap: Span,
    val news: PathNewsCell,
    val home: HomeCell = HomeCell(news.wake),
)
