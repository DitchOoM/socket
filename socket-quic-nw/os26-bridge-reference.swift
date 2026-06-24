import Network
import Security
import Foundation

setvbuf(stdout, nil, _IONBF, 0) // unbuffered so prints show even if we hang/kill
// Hard watchdog: never hang forever.
Task.detached { try? await Task.sleep(nanoseconds: 25_000_000_000); print("WATCHDOG: timed out"); exit(3) }

// Isolate: does inboundStreams deliver a peer-opened stream AT ALL? Client opens a bidi stream to the
// server; server accepts via inboundStreams and reads it. No datagrams.

let p12Path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "socket-quic-nw/testcerts/localhost.p12"
func loadIdentity(_ path: String) -> sec_identity_t? {
    guard let data = FileManager.default.contents(atPath: path) else { return nil }
    var items: CFArray?
    let st = SecPKCS12Import(data as CFData, [kSecImportExportPassphrase as String: "testpass", kSecImportToMemoryOnly as String: true] as CFDictionary, &items)
    guard st == errSecSuccess, let arr = items as? [[String: Any]], let f = arr.first,
          let raw = f[kSecImportItemIdentity as String] else { return nil }
    return sec_identity_create(raw as! SecIdentity)
}
guard let identity = loadIdentity(p12Path) else { print("no identity"); exit(2) }
let alpn = ["spike"]
func q() -> QUIC {
    QUIC(alpn: alpn).idleTimeout(30000).maxDatagramFrameSize(1200).initialMaxData(1<<20)
        .initialMaxStreamDataBidirectionalRemote(1<<20).initialMaxStreamDataBidirectionalLocal(1<<20)
        .initialMaxStreamDataUnidirectional(1<<20)
        .initialMaxBidirectionalStreams(128).initialMaxUnidirectionalStreams(128)
}

actor Box { var s: String? = nil; func set(_ v: String) { if s == nil { s = v } }; func got() -> Bool { s != nil } }
let box = Box()

let listener = try NetworkListener { q().tls.localIdentity(identity) }
let portStream = AsyncStream<UInt16> { c in
    listener.onStateUpdate { l, st in if case .ready = st, let p = l.port { c.yield(p.rawValue); c.finish() } }
}

try await withThrowingTaskGroup(of: Void.self) { group in
    group.addTask {
        do {
            try await listener.run { conn in
                print("server accepted, state=\(conn.state)")
                // Drive readiness + give the client's inboundStreams time, then OPEN a stream to it.
                do {
                    let dg = try await conn.datagrams
                    let dm = try await dg.receive()
                    print("server got datagram: \(String(decoding: dm.content, as: UTF8.self))")
                    try await dg.send(Data("srv-dg".utf8))
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                    let s = try await conn.openStream(directionality: .bidirectional)
                    print("server opened stream id=\(s.streamID) init=\(s.initiator)")
                    try await s.send(Data("srv-to-cli".utf8), endOfStream: true)
                    print("server sent")
                    try? await Task.sleep(nanoseconds: 20_000_000_000)
                } catch { print("server open err: \(error)") }
            }
        } catch { print("listener.run ended: \(error)") }
    }

    var port: UInt16 = 0
    for await p in portStream { port = p; break }
    print("listener ready on \(port)")

    let conn = NetworkConnection(to: .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)) {
        q().tls.certificateValidator { _, _ in true }
    }
    let ready = AsyncStream<Bool> { c in
        conn.onStateUpdate { _, st in
            switch st { case .ready: c.yield(true); c.finish(); case .failed(let e): print("client failed \(e)"); c.yield(false); c.finish(); default: break }
        }
    }
    conn.start()
    for await _ in ready { break }
    print("client ready")

    group.addTask {
        do {
            let dg = try await conn.datagrams
            try await dg.send(Data("cli-dg".utf8))
            let dm = try await dg.receive()
            print("client got datagram: \(String(decoding: dm.content, as: UTF8.self))")
        } catch { print("client dg err: \(error)") }
    }
    group.addTask {
        do {
            try await conn.inboundStreams { stream in
                print("client inboundStreams FIRED id=\(stream.streamID) init=\(stream.initiator)")
                let m = try await stream.receive(atMost: 65535)
                await box.set(String(decoding: m.content, as: UTF8.self))
                print("client read: \(await box.s ?? "?")")
            }
        } catch { print("client inboundStreams err: \(error)") }
    }

    for _ in 0..<200 { if await box.got() { break }; try await Task.sleep(nanoseconds: 100_000_000) }
    group.cancelAll()
}

print("REV RESULT: clientGotStream=\(await box.s ?? "<none>")")
exit(await box.s == "srv-to-cli" ? 0 : 1)
