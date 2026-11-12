package com.ditchoom.socket.quic

@Suppress("DEPRECATION") // Thread.threadId() is Java 19+; this file also compiles for Android.
internal actual fun currentThreadId(): Long = Thread.currentThread().id
