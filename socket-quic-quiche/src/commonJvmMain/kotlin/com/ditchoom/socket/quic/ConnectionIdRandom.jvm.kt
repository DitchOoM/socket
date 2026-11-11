package com.ditchoom.socket.quic

import kotlin.random.Random

/** [Random.Default] is `ThreadLocalRandom` on the JVM and Android: one generator per thread, nothing shared. */
internal actual val connectionIdRandom: Random = Random.Default
