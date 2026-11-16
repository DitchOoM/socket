package com.ditchoom.socket.quic.core.loop

/** Runs a fully built [loop] on a dedicated platform thread of its own, named [name], until the loop stops. */
internal expect fun startLoop(
    loop: Loop,
    name: String,
)
