@file:Suppress("unused") // Loaded at runtime via multi-release JAR, shadows the commonJvmMain version

package com.ditchoom.socket.quic

/** JDK 21+ shadow of [loadNetShim]: the FFM binding, in the process that loads quiche through FFM. */
internal fun loadNetShim(): NetShim = ffmNetShim

private val ffmNetShim: NetShim by lazy { FfmNetShim.create(extractedJniShim()) }
