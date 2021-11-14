import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

abstract class EchoWebsocketTask: DefaultTask() {
    @get:Input
    abstract val port: Property<Int>


    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor



    @TaskAction
    fun startWebsocketServer() {
        val workQueue = getWorkerExecutor().noIsolation()
        workQueue.submit(EchoWebsocketServer::class.java) { parameters ->
            parameters.port = port.get()
        }
    }
}


interface EchoWebsocketParameters: WorkParameters {
    var port :Int
}

private var serverGlobal: NettyApplicationEngine? = null

abstract class EchoWebsocketServer: WorkAction<EchoWebsocketParameters> {

    override fun execute() {
        if (serverGlobal != null) {
            return
        }
        val path = "/echo"
        println("Starting echo websocket server with path \"${path}\" port: ${parameters.port}")
        val server = embeddedServer(Netty, port = parameters.port) {
            install(WebSockets)
            routing {
                webSocket(path) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val receivedText = frame.readText()
                            if (receivedText.equals("bye", ignoreCase = true)) {
                                close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                            } else {
                                send(Frame.Text(receivedText))
                            }
                        } else if (frame is Frame.Binary) {
                            val receivedBinary = frame.data
                            send(Frame.Binary(true, receivedBinary))
                        }
                    }
                }
            }
        }
        try {
            server.start(false)
        } catch (t: Throwable) {
            println("Failed to start echo websocket server, it might already be running")
        }
        serverGlobal = server
    }
}