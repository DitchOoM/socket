package com.ditchoom.socket.quic

/** The [NetShim] of this runtime: JNI here; on JDK 21+ the multi-release jar shadows this with the FFM binding. */
internal fun loadNetShim(): NetShim = JniNetShim
