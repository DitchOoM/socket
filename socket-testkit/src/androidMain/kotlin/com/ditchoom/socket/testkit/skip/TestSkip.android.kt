package com.ditchoom.socket.testkit.skip

internal actual fun testkitEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }
