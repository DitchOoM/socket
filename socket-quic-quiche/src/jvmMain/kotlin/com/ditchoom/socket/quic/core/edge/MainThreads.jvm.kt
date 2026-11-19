package com.ditchoom.socket.quic.core.edge

/** A desktop JVM's UI thread is known by its main dispatcher (Swing, JavaFX). */
internal actual val mainThreadsOfThisRuntime: MainThreads = MainThreads.ByDispatcher
