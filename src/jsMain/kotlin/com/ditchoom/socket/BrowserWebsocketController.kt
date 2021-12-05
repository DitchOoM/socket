package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketDataRead
import kotlinx.coroutines.channels.Channel
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.WebSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
class BrowserWebsocketController(
    connectionOptions: WebSocketConnectionOptions,
) : com.ditchoom.websocket.WebSocket {
    private val url = "ws://${connectionOptions.name}:${connectionOptions.port}${connectionOptions.websocketEndpoint}"
    private val websocket :WebSocket = WebSocket(url, connectionOptions.protocol)

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
            websocket.onclose = {
                isConnected = false
                console.error("\r\nonclose $it")
                continuation.resumeWithException(Exception(it.toString()))
                Unit
            }
            websocket.onerror = {
                isConnected = false
                console.error("\r\nws error", it)
                Unit
            }
            websocket.onopen = { event ->
                isConnected = true
//                console.log("\r\nconnection opened", event)
                continuation.resume(Unit)
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

    override suspend fun ping() {/*Not surfaced on browser*/}

    override suspend fun write(buffer: PlatformBuffer) {
        val arrayBuffer = (buffer as JsBuffer).buffer.buffer.slice(0, buffer.limit().toInt())
        websocket.send(arrayBuffer)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        write(buffer)
        return buffer.limit().toInt()
    }

    override suspend fun close() {
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