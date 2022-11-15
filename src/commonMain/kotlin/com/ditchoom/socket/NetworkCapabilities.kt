package com.ditchoom.socket

enum class NetworkCapabilities {
    FULL_SOCKET_ACCESS,
    WEBSOCKETS_ONLY
}

expect fun getNetworkCapabilities(): NetworkCapabilities
