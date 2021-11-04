package com.ditchoom.socket

import com.ditchoom.buffer.*
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
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
    private val scope: CoroutineScope,
    connectionOptions: WebSocketConnectionOptions
) : SocketController {

    private val websocket :WebSocket =
        WebSocket("ws://${connectionOptions.name}:${connectionOptions.port}${connectionOptions.websocketEndpoint}")

    private var isConnected = false

    private val reader = SuspendableReader(scope, websocket)

    init {
        websocket.binaryType = BinaryType.ARRAYBUFFER
    }

    override fun isOpen() = isConnected

    suspend fun connect() {
        suspendCoroutine<Unit> { continuation ->
            websocket.onclose = {
                isConnected = false
                console.error("onclose $it")
                continuation.resumeWithException(Exception(it.toString()))
                launchClose()
                Unit
            }
            websocket.onerror = {
                isConnected = false
                console.error("ws error", it)
                Unit
            }
            websocket.onopen = { event ->
                isConnected = true
                continuation.resume(Unit)
                Unit
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

    fun launchClose() = scope.launch {
        close()
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
                    incomingChannel.send(buffer)
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