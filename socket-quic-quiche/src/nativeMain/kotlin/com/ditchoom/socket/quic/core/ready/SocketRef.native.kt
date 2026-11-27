package com.ditchoom.socket.quic.core.ready

/** A socket as the native poller watches it, read again whenever the poller adds it. */
internal actual class SocketRef(
    private val current: () -> PollHandle,
) {
    fun handle(): PollHandle = current()
}

internal actual fun SocketRef.pollHandle(): PollHandle = handle()

private val notPolled = SocketRef { PollHandle.NotPolled }

internal actual fun notPolledRef(): SocketRef = notPolled
