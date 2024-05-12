package com.ditchoom.socket

actual fun getNetworkCapabilities(): NetworkCapabilities =
    if (isNodeJs) {
        NetworkCapabilities.FULL_SOCKET_ACCESS
    } else {
        NetworkCapabilities.WEBSOCKETS_ONLY
    }
