package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketDataRead
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.CloseEvent
import org.w3c.dom.WebSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

class BrowserWebsocketController(
    connectionOptions: WebSocketConnectionOptions,
) : com.ditchoom.websocket.WebSocket {
    private val url = "ws://${connectionOptions.name}:${connectionOptions.port}${connectionOptions.websocketEndpoint}"
    private val websocket: WebSocket = if (connectionOptions.protocol != null) {
        WebSocket(url, connectionOptions.protocol)
    } else {
        WebSocket(url)
    }
    private var wasCloseInitiatedClientSize = false
    private val disconnectedFlow =
        MutableSharedFlow<SocketException>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var isConnected = false
    private val incomingChannel = Channel<WebSocketDataRead>()

    init {
        websocket.binaryType = BinaryType.ARRAYBUFFER
        websocket.onmessage = {
            val data = it.data
//            console.log("\r\non msg", it.data)
            when (data) {
                is ArrayBuffer -> {
                    val array = Uint8Array(data)
                    val buffer = JsBuffer(array)
                    buffer.setLimit(array.length)
                    buffer.setPosition(0)
                    incomingChannel.trySend(WebSocketDataRead.BinaryWebSocketDataRead(buffer)).getOrThrow()
                }
                is CharSequence -> {
                    incomingChannel.trySend(WebSocketDataRead.CharSequenceWebSocketDataRead(data)).getOrThrow()
                }
                else -> {
                    throw IllegalArgumentException("Received invalid message type!")
                }
            }
            Unit
        }
    }

    override fun isOpen() = isConnected

    suspend fun connect() {
        suspendCoroutine<Unit> { continuation ->
            var resumed = false
            var error: SocketException? = null
            websocket.onclose = {
                val closeEvent = it as CloseEvent
                isConnected = false
                incomingChannel.close()
                val closeException = SocketException(
                    "Socket closed reason:${closeEvent.reason}, code:${closeEvent.code}, wasClean: ${closeEvent.wasClean}",
                    wasCloseInitiatedClientSize,
                    error
                )
                disconnectedFlow.tryEmit(closeException)
                if (!resumed) {
                    continuation.resumeWithException(closeException)
                    resumed = true
                }
                Unit
            }
            websocket.onerror = {
                isConnected = false
                error = SocketException("WS onError: ${it.asDynamic().code}")
                Unit
            }
            websocket.onopen = { event ->
                isConnected = true
                if (!resumed) {
                    continuation.resume(Unit)
                    resumed = true
                }
                Unit
            }
        }
    }

    override suspend fun read(): WebSocketDataRead {
        return incomingChannel.receive()
    }

    override suspend fun readData(timeout: Duration) = when (val data = read()) {
        is WebSocketDataRead.BinaryWebSocketDataRead -> data.data
        else -> throw IllegalArgumentException("Unable to read binary data when received string data")
    }

    override suspend fun write(string: String) {
        websocket.send(string)
    }

    override suspend fun ping() {/*Not surfaced on browser*/
    }

    override suspend fun write(buffer: PlatformBuffer) {
        val arrayBuffer = (buffer as JsBuffer).buffer.buffer.slice(0, buffer.limit())
        websocket.send(arrayBuffer)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        write(buffer)
        return buffer.limit()
    }

    override suspend fun awaitClose() = disconnectedFlow.asSharedFlow().first()

    override suspend fun close() {
        wasCloseInitiatedClientSize = true
        incomingChannel.close()
        websocket.close()
    }

    companion object {
        suspend fun open(webSocketConnectionOptions: WebSocketConnectionOptions): BrowserWebsocketController {
            val controller = BrowserWebsocketController(webSocketConnectionOptions)
            controller.connect()
            return controller
        }
    }
}