// TCP 64 B echo on Network.framework, the platform's own API (and what this library's Apple TCP path sits on).
// Client and server NWConnections share the process; completion handlers run on one serial dispatch queue per
// connection (libdispatch's thread pool); the measuring thread blocks on a semaphore until the echo arrives.
import Foundation
import Network
import Rec

let size = 64
let serverQueue = DispatchQueue(label: "server")
let listener = try NWListener(using: .tcp, on: .any)
let ready = DispatchSemaphore(value: 0)
var port: NWEndpoint.Port = 0

func echo(_ c: NWConnection) {
    c.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
        if let data = data, !data.isEmpty {
            c.send(content: data, completion: .contentProcessed { _ in echo(c) })
        } else if isComplete || error != nil {
            c.cancel()
        }
    }
}

listener.newConnectionHandler = { c in
    c.start(queue: serverQueue)
    echo(c)
}
listener.stateUpdateHandler = { state in
    if case .ready = state {
        port = listener.port!
        ready.signal()
    }
}
listener.start(queue: serverQueue)
ready.wait()

let client = NWConnection(host: "127.0.0.1", port: port, using: .tcp)
let connected = DispatchSemaphore(value: 0)
client.stateUpdateHandler = { if case .ready = $0 { connected.signal() } }
client.start(queue: DispatchQueue(label: "client"))
connected.wait()

let request = Data(repeating: UInt8(ascii: "r"), count: size)
let done = DispatchSemaphore(value: 0)
let rec = Recorder(stack: "swift-network-framework", workload: "tcp")
while true {
    let t = DispatchTime.now().uptimeNanoseconds
    client.send(content: request, completion: .contentProcessed { _ in })
    client.receive(minimumIncompleteLength: size, maximumLength: size) { _, _, _, _ in done.signal() }
    done.wait()
    if !rec.record(DispatchTime.now().uptimeNanoseconds - t) { break }
}
exit(0)
