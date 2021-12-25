package com.ditchoom.websocket

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
class WebSocketConnectionOptions(
    val name: String,
    val port: Int,
    val protocol: String? = null,
    val websocketEndpoint: String?,
    val connectionTimeout: Duration,
    val readTimeout: Duration = connectionTimeout,
    val writeTimeout: Duration = connectionTimeout,
)