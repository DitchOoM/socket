package com.ditchoom.socket

import cocoapods.SocketWrapper.ClientSocketWrapper
import kotlinx.cinterop.convert
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class NWClientSocketWrapper(val useTls: Boolean) : NWSocketWrapper(), ClientToServerSocket {

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
    ) {
        val socket = suspendCancellableCoroutine {
            val socketWrapper = ClientSocketWrapper(
                hostname ?: "localhost",
                port.toUShort(),
                timeout.inWholeSeconds.convert(),
                useTls
            ) { socket, errorString, _, isDnsError, _ ->

                if (errorString != null) {
                    if (isDnsError) {
                        it.resumeWithException(SocketUnknownHostException(errorString))
                    } else {
                        it.resumeWithException(SocketException(errorString))
                    }
                } else if (socket != null) {
                    it.resume(socket)
                } else {
                    it.resumeWithException(IllegalStateException("Failed to get a valid socket or error message"))
                }
            }
            it.invokeOnCancellation {
                socketWrapper.closeWithCompletionHandler { }
            }
        }
        this.socket = socket
    }
}
