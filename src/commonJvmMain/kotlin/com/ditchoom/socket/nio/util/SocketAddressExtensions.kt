package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketUnknownHostException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun buildInetAddress(
    port: Int,
    hostname: String?,
): InetSocketAddress =
    if (hostname != null) {
        try {
            InetSocketAddress(hostname.asInetAddress(), port)
        } catch (e: Exception) {
            throw SocketUnknownHostException(hostname, cause = e)
        }
    } else {
        suspendCoroutine {
            try {
                it.resume(InetSocketAddress(InetAddress.getLocalHost(), port))
            } catch (e: Exception) {
                it.resumeWithException(
                    SocketUnknownHostException(
                        "hostname is null",
                        cause = e,
                    ),
                )
            }
        }
    }
