package com.ditchoom.websocket

import kotlin.time.Duration

class WebSocketConnectionOptions(
    val name: String,
    val port: Int,
    val protocol: String? = null,
    val websocketEndpoint: String?,
    val connectionTimeout: Duration,
    val readTimeout: Duration = connectionTimeout,
    val writeTimeout: Duration = connectionTimeout,
)