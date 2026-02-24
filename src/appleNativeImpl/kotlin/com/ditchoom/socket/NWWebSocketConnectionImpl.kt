package com.ditchoom.socket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.nwhelpers.nw_helper_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_create_ws_connection
import com.ditchoom.socket.nwhelpers.nw_helper_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_start
import com.ditchoom.socket.nwhelpers.nw_helper_ws_receive_message
import com.ditchoom.socket.nwhelpers.nw_helper_ws_send
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Network.nw_connection_t
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
internal class NWWebSocketConnectionImpl(
    private val connection: nw_connection_t,
) : NativeWebSocketConnection {
    @Volatile
    internal var ready = false

    @Volatile
    private var closedLocally = false

    override val isOpen: Boolean get() = ready && !closedLocally

    override suspend fun receiveMessage(): NativeWebSocketMessage {
        val conn = connection ?: throw SocketClosedException("WebSocket is closed")
        if (closedLocally) throw SocketClosedException("WebSocket is closed")

        return suspendCancellableCoroutine { continuation ->
            nw_helper_ws_receive_message(conn) { data, opcode, closeCode, _, error ->
                if (error != null) {
                    continuation.resumeWithException(
                        SocketException(error.localizedDescription),
                    )
                } else {
                    val buffer = if (data != null && data.length.toInt() > 0) {
                        val buf = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                        buf.position(data.length.toInt())
                        buf
                    } else {
                        null
                    }
                    continuation.resume(
                        NativeWebSocketMessage(
                            data = buffer,
                            opcode = opcode,
                            closeCode = closeCode,
                        ),
                    )
                }
            }
            continuation.invokeOnCancellation {
                nw_helper_cancel(conn)
            }
        }
    }

    override suspend fun sendMessage(
        data: ReadBuffer?,
        opcode: Int,
        closeCode: Int,
    ) {
        val conn = connection ?: throw SocketClosedException("WebSocket is closed")
        if (closedLocally) throw SocketClosedException("WebSocket is closed")

        val nsData: NSData? = data?.toNSData()

        suspendCancellableCoroutine<Unit> { continuation ->
            nw_helper_ws_send(conn, nsData, opcode, closeCode) { error ->
                if (error != null) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(SocketException(error.localizedDescription))
                    }
                } else {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }

    override suspend fun close() {
        if (closedLocally) return
        closedLocally = true
        ready = false

        val conn = connection ?: return
        // Send close frame, then cancel
        try {
            sendMessage(null, NativeWebSocketConnection.OPCODE_CLOSE, 1000) // normal closure
        } catch (_: Exception) {
            // Ignore errors during close
        }
        nw_helper_cancel(conn)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun connectNativeWebSocket(
    url: String,
    tls: Boolean,
    verifyCertificates: Boolean,
    autoReplyPing: Boolean,
    subprotocols: List<String>?,
    timeoutSeconds: Int,
): NativeWebSocketConnection {
    val conn = nw_helper_create_ws_connection(
        url = url,
        use_tls = NSNumber(bool = tls),
        verify_certs = NSNumber(bool = verifyCertificates),
        auto_reply_ping = NSNumber(bool = autoReplyPing),
        subprotocols = subprotocols,
        timeout_seconds = timeoutSeconds,
    ) ?: throw IllegalArgumentException("Failed to create WebSocket connection for URL: $url")

    val impl = NWWebSocketConnectionImpl(conn)

    // Wait for ready state
    // Connection state: 0=invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
    suspendCancellableCoroutine<Unit> { continuation ->
        var resumed = false

        nw_helper_set_state_handler(conn) { state, errorDomain, _, errorDesc ->
            if (resumed) return@nw_helper_set_state_handler

            when (state) {
                3 -> { // ready
                    resumed = true
                    impl.ready = true
                    continuation.resume(Unit)
                }
                1, 4 -> { // waiting or failed
                    resumed = true
                    continuation.resumeWithException(
                        NWSocketWrapper.mapSocketException(errorDomain, errorDesc),
                    )
                }
                5 -> { // cancelled
                    resumed = true
                    continuation.resumeWithException(
                        SocketException(errorDesc ?: "Connection cancelled"),
                    )
                }
            }
        }

        nw_helper_start(conn)

        continuation.invokeOnCancellation {
            if (!resumed) {
                nw_helper_cancel(conn)
            }
        }
    }

    return impl
}
