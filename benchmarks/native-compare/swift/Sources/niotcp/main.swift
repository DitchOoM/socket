// TCP 64 B echo on SwiftNIO (the nearest thing to Netty on Apple platforms): a MultiThreadedEventLoopGroup with
// one event loop per core; client and server channels share it. The measuring thread blocks on a semaphore
// until the client handler has seen the whole echo.
import Foundation
import NIOCore
import NIOPosix
import Rec

let size = 64
let echoDone = DispatchSemaphore(value: 0)

final class Echo: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer
    typealias OutboundOut = ByteBuffer
    func channelRead(context: ChannelHandlerContext, data: NIOAny) { context.write(data, promise: nil) }
    func channelReadComplete(context: ChannelHandlerContext) { context.flush() }
}

final class Collect: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer
    var got = 0
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        got += unwrapInboundIn(data).readableBytes
        if got >= size {
            got = 0
            echoDone.signal()
        }
    }
}

let group = MultiThreadedEventLoopGroup(numberOfThreads: System.coreCount)
let server = try ServerBootstrap(group: group)
    .childChannelInitializer { $0.pipeline.addHandler(Echo()) }
    .bind(host: "127.0.0.1", port: 0).wait()
let client = try ClientBootstrap(group: group)
    .channelInitializer { $0.pipeline.addHandler(Collect()) }
    .connect(to: server.localAddress!).wait()

var request = client.allocator.buffer(capacity: size)
request.writeBytes([UInt8](repeating: UInt8(ascii: "r"), count: size))
let rec = Recorder(stack: "swift-nio", workload: "tcp")
while true {
    let t = DispatchTime.now().uptimeNanoseconds
    client.writeAndFlush(request, promise: nil)
    echoDone.wait()
    if !rec.record(DispatchTime.now().uptimeNanoseconds - t) { break }
}
exit(0)
