package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where this module's public suspend functions do work that blocks a thread (key generation reading
 * the OS entropy source), so it never runs on the caller's thread, which may be the Android main
 * thread or a desktop UI thread. `Dispatchers.IO` wherever one exists; JS has one thread and nothing to
 * hand the work to.
 */
internal expect val blockingWork: CoroutineDispatcher
