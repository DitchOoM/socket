package com.ditchoom.socket.quic

/**
 * Loads the appropriate [QuicheApi] for the current JDK.
 * On JDK 21+, the multi-release JAR shadows this with the FFM version.
 */
fun loadQuicheApi(): QuicheApi = JniQuicheApi
