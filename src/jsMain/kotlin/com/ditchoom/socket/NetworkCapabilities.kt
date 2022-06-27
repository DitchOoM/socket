package com.ditchoom.socket


actual fun getNetworkCapabilities() = if (isNodeJs) {
    NetworkCapabilities.FULL_SOCKET_ACCESS
} else {
    NetworkCapabilities.WEBSOCKETS_ONLY
}

