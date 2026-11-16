package com.ditchoom.socket.quic

/**
 * The poller calls whose refusal nobody can act on but the loop's containment: a refusal is thrown as a
 * [NativeSocketRefusal], as the JDK selector throws its `IOException`, and the loop ends its connections with it.
 */
internal fun NetShim.checkedWait(
    poller: Long,
    mode: Int,
    nanos: Long,
): Int {
    val result = pollerWait(poller, mode, nanos)
    if (result < 0) throw NativeSocketRefusal(SocketRefusal.of(-result))
    return result
}

internal fun NetShim.checkedAdd(
    poller: Long,
    fd: Int,
    token: Int,
) = checked(pollerAdd(poller, fd, token))

internal fun NetShim.checkedModify(
    poller: Long,
    fd: Int,
    token: Int,
    writable: Int,
) = checked(pollerModify(poller, fd, token, writable))

internal fun NetShim.checkedWake(poller: Long) = checked(pollerWake(poller))

private fun checked(result: Int) {
    if (result < 0) throw NativeSocketRefusal(SocketRefusal.of(-result))
}
