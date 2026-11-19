package com.ditchoom.socket.quic.core.edge

import android.os.Looper

/**
 * The main looper's thread, which Android's thread policy forbids network calls on, blocking or not. Read once:
 * `Looper.getMainLooper` takes a lock, and the check runs on every read and write.
 */
internal actual val mainThreadsOfThisRuntime: MainThreads = MainThreads.Known(MainThread(Looper.getMainLooper().thread))
