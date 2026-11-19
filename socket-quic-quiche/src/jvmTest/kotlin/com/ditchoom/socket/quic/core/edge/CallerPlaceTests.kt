package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.CallerPlace
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a caller's work runs: a main dispatcher hands it to the loop on every runtime, and where the runtime knows its
 * main thread (Android's main looper), a caller on that thread hands it over whatever its dispatcher. The Android lane
 * runs the same rule against the real main looper (`CoreMainLooperPlacementTests`).
 */
class CallerPlaceTests {
    @Test
    fun aMainDispatcherHandsWorkToTheLoopWhateverTheRuntime() {
        assertEquals(CallerPlace.HandToLoop, callerPlaceOf(SomeMain, MainThreads.ByDispatcher))
        assertEquals(CallerPlace.HandToLoop, callerPlaceOf(SomeMain, MainThreads.Known(MainThread(Thread()))))
    }

    @Test
    fun aCallerOnAKnownMainThreadHandsWorkToTheLoopUnderAnyDispatcher() {
        assertEquals(CallerPlace.HandToLoop, callerPlaceOf(EmptyCoroutineContext, MainThreads.Known(MainThread(Thread.currentThread()))))
    }

    @Test
    fun anyOtherCallerMayEnter() {
        assertEquals(CallerPlace.MayEnter, callerPlaceOf(EmptyCoroutineContext, MainThreads.Known(MainThread(Thread()))))
        assertEquals(CallerPlace.MayEnter, callerPlaceOf(EmptyCoroutineContext, MainThreads.ByDispatcher))
    }

    @Test
    fun aDesktopJvmKnowsItsMainThreadByItsDispatcher() {
        assertEquals<MainThreads>(MainThreads.ByDispatcher, mainThreadsOfThisRuntime)
    }

    /** A main dispatcher nothing is dispatched to. */
    private object SomeMain : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = block.run()
    }
}
