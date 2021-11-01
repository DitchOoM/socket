package com.ditchoom.socket

import com.ditchoom.buffer.*
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.WebSocket
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
class BrowserWebsocketController(
    scope: CoroutineScope,
    connectionOptions: WebSocketConnectionOptions
) : SocketController {

    private val websocket: WebSocket =
        WebSocket(
            "ws://${connectionOptions.name}:${connectionOptions.port}${connectionOptions.websocketEndpoint}",
            "mqtt"
        )
    private var isConnected = false

    private val reader = SuspendableReader(scope, websocket)

    init {
        websocket.binaryType = BinaryType.ARRAYBUFFER
        websocket.onclose = {
            isConnected = false
            Unit
        }
        websocket.onerror = {
            isConnected = false
            println("websocket onerror $it")
            Unit
        }
    }

    override fun isOpen(): Boolean {
        return isConnected
    }

    suspend fun connect() {
        suspendCoroutine<Unit> { continuation ->
            websocket.onopen = { event ->
                isConnected = true
                continuation.resume(Unit)
            }
            websocket.onerror = { event ->
                println("error ${event.type}")
            }
        }
    }

    override suspend fun readBuffer(timeout: Duration): SocketDataRead<ReadBuffer> {
        val buffer = reader.incomingChannel.receive()
        return SocketDataRead(buffer, buffer.remaining().toInt())
    }

    override suspend fun writeFully(buffer: PlatformBuffer, timeout: Duration) {
        val arrayBuffer = (buffer as JsBuffer).buffer.buffer.slice(buffer.position().toInt(), buffer.limit().toInt())
        websocket.send(arrayBuffer)
    }

    override suspend fun close() {
        reader.incomingChannel.close()
        websocket.close()
    }

    class SuspendableReader(scope: CoroutineScope, webSocket: WebSocket) {
        internal val incomingChannel = Channel<JsBuffer>()
        private var currentBuffer: JsBuffer? = null

        init {
            webSocket.onmessage = {
                val arrayBuffer = it.data as ArrayBuffer
                val array = Uint8Array(arrayBuffer)
                val buffer = JsBuffer(array)
                buffer.setLimit(array.length)
                buffer.setPosition(0)
                scope.promise {
                    println("sending")
                    incomingChannel.send(buffer)
                    println("sent")
                }
                Unit
            }
        }
    }

    companion object {
        suspend fun open(scope: CoroutineScope, webSocketConnectionOptions: WebSocketConnectionOptions): BrowserWebsocketController {
            val controller = BrowserWebsocketController(scope, webSocketConnectionOptions)
            controller.connect()
            return controller
        }
    }
}