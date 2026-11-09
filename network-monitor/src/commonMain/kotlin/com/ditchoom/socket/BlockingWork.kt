package com.ditchoom.socket

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where this module's public suspend functions do work that blocks a thread (reading the interface
 * table, opening a routing socket, loading a native library), so it never runs on the caller's thread,
 * which may be the Android main thread or a desktop UI thread. `Dispatchers.IO` wherever one exists;
 * JS has one thread and nothing to hand the work to.
 */
internal expect val blockingWork: CoroutineDispatcher
