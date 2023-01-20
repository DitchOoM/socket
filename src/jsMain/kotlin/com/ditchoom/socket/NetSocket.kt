package com.ditchoom.socket

import org.khronos.webgl.Uint8Array

@JsModule("net")
@JsNonModule
external class Net {
    companion object {
        fun connect(options: Options, connectListener: () -> Unit): Socket
        fun createServer(connectionListener: (Socket) -> Unit = definedExternally): Server
    }
}

@JsModule("tls")
@JsNonModule
external class Tls {
    companion object {
        fun connect(tcpOptions: Options, connectListener: () -> Unit): Socket
    }
}

external class Server {
    fun address(): IpAddress?
    fun close(callback: () -> Unit): Server
    fun getConnections(callback: (err: Any, count: Int) -> Unit): Server
    fun listen(
        port: Int = definedExternally,
        host: String = definedExternally,
        backlog: Int = definedExternally,
        callback: () -> Unit = definedExternally
    ): Server

    var listening: Boolean = definedExternally
    var maxConnections: Int = definedExternally
}

external class IpAddress {
    val port: Int
    val family: String
    val address: String
}

external class Socket {
    var localPort: Int
    var remotePort: Int
    var remoteAddress: String?
    fun write(data: Uint8Array, callback: () -> Unit): Boolean
    fun on(event: String, callback: (Any) -> Unit)
    fun resume(): Socket
    fun end(callback: () -> Unit): Socket
    fun destroy(): Socket
}

class OnRead(
    @JsName("buffer")
    var buffer: (() -> Uint8Array)? = null,
    @JsName("callback")
    var callback: ((Int, Uint8Array) -> Boolean)? = null
)

@JsName("options")
class Options(
    @JsName("port")
    val port: Int,
    @JsName("host")
    val host: String? = null,
    @JsName("onread")
    val onread: OnRead? = null,
    @JsName("rejectUnauthorized")
    val rejectUnauthorized: Boolean = true,
)
