import Foundation
import Network
import Security
import CryptoKit

// OS-26 Swift→Kotlin/Native QUIC bridge (issue #173). The macOS/iOS 26 `NetworkConnection<QUIC>`
// Swift API models datagrams AND inbound streams as first-class members of the SAME connection — the
// thing the legacy `nw_connection_group` backend cannot do (extracting its datagram flow suppresses
// inbound-stream delivery). Kotlin/Native has Obj-C interop only, not Swift, so this thin shim wraps
// the Swift-only API (generics / async / AsyncSequence / result builders) behind a concrete `@objc`
// surface: caller-initiated ops use one-shot completion blocks; peer-initiated events use stored push
// handlers backed by Swift serving Tasks (owned here), which Kotlin re-wraps into its Channel patterns.
// See OS26_SWIFT_BRIDGE_PLAN.md ("Target API surface") and os26-bridge-reference.swift (proven loopback).
//
// State codes marshaled to Kotlin (matching nw_helper_quic_set_state_handler's mapping):
//   0=setup/invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled.

// --- env-gated lifecycle diagnostics (QUIC_NW_DIAG) ---
// Set QUIC_NW_DIAG=1 to trace the in-process NW↔NW QUIC loopback lifecycle to stderr with a
// monotonic millisecond stamp. Built to nail the macos-26 CI-only race (PR #176): which inbound
// stream NW actually delivers (or drops), and the exact connection state at an ENOTCONN write.
// Zero cost when unset (a single bool check). Stderr (not stdout) so it survives a test hang/kill.
private let nwDiagEnabled = ProcessInfo.processInfo.environment["QUIC_NW_DIAG"] != nil
private let nwDiagStartNs = DispatchTime.now().uptimeNanoseconds
private let nwDiagSeqLock = NSLock()
private var nwDiagSeq = 0

private func nwDiagNextSeq() -> Int {
    nwDiagSeqLock.lock()
    defer { nwDiagSeqLock.unlock() }
    nwDiagSeq += 1
    return nwDiagSeq
}

private func nwDiag(_ message: @autoclosure () -> String) {
    guard nwDiagEnabled else { return }
    let ms = Double(DispatchTime.now().uptimeNanoseconds &- nwDiagStartNs) / 1_000_000.0
    let line = "[NWQ26 +\(String(format: "%8.1f", ms))ms] \(message())\n"
    FileHandle.standardError.write(Data(line.utf8))
}

@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
private func makeQuic(alpn: [String], idleTimeoutMs: Int, maxDatagramFrameSize: Int) -> QUIC {
    // Generous stream credits + data windows: the new API defaults these low/0, which STARVES a
    // peer-opened stream (spike gotcha), so mirror the legacy NW_QUIC_MAX_STREAMS=1024 intent. idle
    // timeout is MILLISECONDS here (`.idleTimeout(30)` would idle-kill at 30 ms — another spike gotcha).
    var q =
        QUIC(alpn: alpn)
            .idleTimeout(idleTimeoutMs)
            .initialMaxData(1 << 20)
            .initialMaxStreamDataBidirectionalRemote(1 << 20)
            .initialMaxStreamDataBidirectionalLocal(1 << 20)
            .initialMaxStreamDataUnidirectional(1 << 20)
            .initialMaxBidirectionalStreams(128)
            .initialMaxUnidirectionalStreams(128)
    if maxDatagramFrameSize > 0 {
        q = q.maxDatagramFrameSize(maxDatagramFrameSize)
    }
    return q
}

/// Map a `NetworkChannel.State` (connection) to the Kotlin-side integer + extracted NWError fields.
@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
private func mapConnState(_ state: NetworkConnection<QUIC>.State) -> (Int32, Int32, String?) {
    switch state {
    case .setup: return (0, 0, nil)
    case .waiting(let e): return (1, Int32(truncatingIfNeeded: nwErrorCode(e)), "\(e)")
    case .preparing: return (2, 0, nil)
    case .ready: return (3, 0, nil)
    case .failed(let e): return (4, Int32(truncatingIfNeeded: nwErrorCode(e)), "\(e)")
    case .cancelled: return (5, 0, nil)
    @unknown default: return (0, 0, nil)
    }
}

/// Best-effort POSIX/numeric code out of an NWError for diagnostics (the description carries the detail).
@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
private func nwErrorCode(_ error: NWError) -> Int {
    switch error {
    case .posix(let code): return Int(code.rawValue)
    case .dns(let code): return Int(code)
    case .tls(let code): return Int(code)
    @unknown default: return -1
    }
}

@objc public final class NWQuic26Bridge: NSObject {
    /// Connect a QUIC client. Returns the connection handle synchronously; `onReady` fires once on
    /// `.ready` (errCode 0) or on the first terminal failure (errCode != 0, with a description). When
    /// [serverCertificateHashes] is non-nil each entry is a 32-byte SHA-256 leaf pin compared in Swift
    /// (W3C `serverCertificateHashes`); nil keeps Network.framework's default trust evaluation.
    @available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
    @objc public func connect(
        host: NSString,
        port: UInt16,
        alpn: NSArray,
        idleTimeoutMs: Int32,
        maxDatagramFrameSize: Int32,
        keepAliveMs: Int32,
        serverCertificateHashes: NSArray?,
        requireChain: Bool,
        verifyPeer: Bool,
        onReady: @escaping (Int32, NSString?) -> Void
    ) -> NWQuic26Conn {
        let alpnList = (alpn as? [String]) ?? []
        let pins: [Data]? = (serverCertificateHashes as? [NSData])?.map { $0 as Data }

        let conn = NWQuic26Conn(
            datagramsEnabled: maxDatagramFrameSize > 0,
            isServer: false,
            keepAliveMs: Int(keepAliveMs)
        )

        let quic: QUIC = {
            let base = makeQuic(
                alpn: alpnList,
                idleTimeoutMs: Int(idleTimeoutMs),
                maxDatagramFrameSize: Int(maxDatagramFrameSize)
            )
            if let pins {
                // Hash-pin validator (approach C): hash the peer leaf DER and compare in Swift. Returning
                // true accepts (so a self-signed but pinned leaf validates); record a typed failure reason
                // + the computed hash / matched leaf DER for Kotlin to read on rejection. The pin IS the
                // trust check (W3C serverCertificateHashes), independent of verifyPeer.
                return base.tls.certificateValidator { _, trust in
                    conn.evaluatePin(trust: trust, expectedHashes: pins, requireChain: requireChain)
                }
            }
            if !verifyPeer {
                // verifyPeer=false (insecure / loopback self-signed): accept any peer certificate. This is
                // the new-API analog of the legacy verify_block returning true; without it NW runs default
                // system trust and rejects a self-signed leaf.
                return base.tls.certificateValidator { _, _ in true }
            }
            return base // default NW trust evaluation (trustedCaCertificatesPem anchors: TODO, not yet wired)
        }()

        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host as String),
            port: NWEndpoint.Port(rawValue: port) ?? .any
        )
        let connection = NetworkConnection(to: endpoint) { quic }
        conn.attach(connection: connection, onReady: onReady)
        nwDiag("\(conn.diagTag) connect() -> \(host):\(port) dg=\(maxDatagramFrameSize > 0) alpn=\(alpnList)")
        _ = connection.start()
        return conn
    }

    /// Bind a QUIC server. The shim imports the PKCS#12 identity itself (with `kSecImportToMemoryOnly`,
    /// so no login-keychain prompt — spike gotcha). `onListenerState` fires with the bound port on
    /// `.ready`; each accepted connection is delivered to `onConnection`. Returns the listener handle.
    @available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
    @objc public func listen(
        host: NSString?,
        port: UInt16,
        alpn: NSArray,
        p12Path: NSString,
        p12Password: NSString,
        idleTimeoutMs: Int32,
        maxDatagramFrameSize: Int32,
        keepAliveMs: Int32,
        onConnection: @escaping (NWQuic26Conn) -> Void,
        onListenerState: @escaping (Int32, UInt16, NSString?) -> Void
    ) -> NWQuic26Listener {
        let alpnList = (alpn as? [String]) ?? []
        let listenerBox = NWQuic26Listener()

        guard let identity = loadIdentity(path: p12Path as String, password: p12Password as String) else {
            onListenerState(-1, 0, "Failed to import PKCS#12 identity from \(p12Path)" as NSString)
            return listenerBox
        }

        let quic = makeQuic(
            alpn: alpnList,
            idleTimeoutMs: Int(idleTimeoutMs),
            maxDatagramFrameSize: Int(maxDatagramFrameSize)
        ).tls.localIdentity(identity)

        let datagramsEnabled = maxDatagramFrameSize > 0
        let keepAliveMsInt = Int(keepAliveMs)

        do {
            let listener = try NetworkListener { quic }
            listener.onStateUpdate { l, state in
                switch state {
                case .ready:
                    onListenerState(0, l.port?.rawValue ?? 0, nil)
                case .failed(let e):
                    onListenerState(Int32(truncatingIfNeeded: nwErrorCode(e)), 0, "\(e)" as NSString)
                case .waiting(let e):
                    onListenerState(Int32(truncatingIfNeeded: nwErrorCode(e)), 0, "\(e)" as NSString)
                default:
                    break
                }
            }
            listenerBox.attach(listener: listener)

            let runTask = Task {
                try await listener.run { connection in
                    let wrapped = NWQuic26Conn(
                        datagramsEnabled: datagramsEnabled,
                        isServer: true,
                        keepAliveMs: keepAliveMsInt
                    )
                    // The connection is already accepted; start serving immediately — obtaining
                    // `datagrams` / `inboundStreams` is what drives the handshake to completion (a passive
                    // ready-wait STALLS establishment, per the spike). Deliver to Kotlin, then keep the
                    // run-closure suspended (its lifetime == the connection's) until Kotlin closes it.
                    nwDiag("\(wrapped.diagTag) listener accepted a connection (state=\(connection.state))")
                    wrapped.attach(connection: connection, onReady: { _, _ in })
                    wrapped.serveAsAccepted()
                    onConnection(wrapped)
                    await wrapped.awaitClosed()
                    nwDiag("\(wrapped.diagTag) run-closure returning (connection closed)")
                }
            }
            listenerBox.attach(runTask: runTask)
        } catch {
            onListenerState(-1, 0, "Failed to create QUIC listener: \(error)" as NSString)
        }
        return listenerBox
    }
}

/// Import a `sec_identity_t` from a PKCS#12 file in-memory only (no keychain side effects / prompts).
@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
private func loadIdentity(path: String, password: String) -> sec_identity_t? {
    guard let data = FileManager.default.contents(atPath: path) else { return nil }
    var items: CFArray?
    let opts: [String: Any] = [
        kSecImportExportPassphrase as String: password,
        kSecImportToMemoryOnly as String: true,
    ]
    let status = SecPKCS12Import(data as CFData, opts as CFDictionary, &items)
    guard status == errSecSuccess,
          let arr = items as? [[String: Any]],
          let first = arr.first,
          let raw = first[kSecImportItemIdentity as String]
    else { return nil }
    return sec_identity_create(raw as! SecIdentity)
}

@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
@objc public final class NWQuic26Conn: NSObject {
    private let datagramsEnabled: Bool
    private let isServer: Bool
    private let keepAliveMs: Int

    private var connection: NetworkConnection<QUIC>?

    // Datagram channel resolved once (obtaining it drives the handshake). Both sendDatagram and the
    // receive loop await this Task's value. The resolved channel is also cached so the synchronous
    // maxDatagramSize() can read its `maximumDatagramSize` (the connection-level `usableDatagramFrameSize`
    // is always 0 — wrong accessor; the channel's value is the real negotiated usable size).
    private var datagramsTask: Task<QUIC.Datagrams<QUICDatagram>, Error>?
    private var datagramsChannel: QUIC.Datagrams<QUICDatagram>?
    private let datagramsLock = NSLock()

    private var stateHandler: ((Int32, Int32, NSString?) -> Void)?
    private var datagramHandler: ((NSData) -> Void)?
    private var inboundStreamHandler: ((NWQuic26Stream, UInt64, Bool, Bool) -> Void)?

    private var serveTasks: [Task<Void, Never>] = []

    // Resumed by close(), unblocking the server run-closure (whose lifetime == connection lifetime).
    private var closedContinuation: CheckedContinuation<Void, Never>?
    private var isClosed = false
    private let closeLock = NSLock()
    private var readyFired = false

    // Pin diagnostics (read by Kotlin after a failed onReady), mirroring the C verify_block out-params.
    @objc public private(set) var pinFailureReason: Int32 = 0 // 0 none, 1 mismatch, 2 no-peer-cert
    @objc public private(set) var pinComputedHashHex: NSString?
    @objc public private(set) var pinMatchedLeafDer: NSData?

    // Stable short tag for QUIC_NW_DIAG traces, e.g. "srv#3" / "cli#4".
    private let connSeq = nwDiagNextSeq()
    var diagTag: String { "\(isServer ? "srv" : "cli")#\(connSeq)" }

    // Graceful-close drain window: how long a clean close() holds the connection alive so NW can flush
    // stream bytes it has accepted but not yet put on the wire (see close()). Bounded so a wedged flush
    // can't leak the connection; runs detached so close() still returns immediately.
    private static let gracefulDrainNanos: UInt64 = 500_000_000

    init(datagramsEnabled: Bool, isServer: Bool, keepAliveMs: Int) {
        self.datagramsEnabled = datagramsEnabled
        self.isServer = isServer
        self.keepAliveMs = keepAliveMs
        super.init()
    }

    /// Wire the underlying connection + fire `onReady` exactly once on the first terminal state.
    func attach(connection: NetworkConnection<QUIC>, onReady: @escaping (Int32, NSString?) -> Void) {
        self.connection = connection
        connection.onStateUpdate { [weak self] _, state in
            let (code, errCode, desc) = mapConnState(state)
            nwDiag("\(self?.diagTag ?? "?") state=\(code) (\(state)) errCode=\(errCode)")
            switch state {
            case .ready:
                if self?.markReadyFiredOnce() == true { onReady(0, nil) }
            case .failed, .cancelled:
                if self?.markReadyFiredOnce() == true {
                    onReady(errCode == 0 ? -1 : errCode, (desc ?? "connection \(state)") as NSString)
                }
            default:
                // .setup / .preparing / .waiting are NON-terminal. In particular `.waiting` carrying a
                // transient POSIX 50 (ENETDOWN) fires mid-handshake on loopback and RECOVERS to `.ready`
                // — so it must NOT resolve onReady (doing so killed the connect before it readied). The
                // only terminal outcomes for connect are .ready / .failed / .cancelled.
                break
            }
            self?.stateHandler?(code, errCode, desc as NSString?)
        }
    }

    /// Claim the one-shot onReady firing; returns true exactly once (NW delivers state serially, but
    /// guard anyway so a post-ready failure never re-fires onReady).
    private func markReadyFiredOnce() -> Bool {
        closeLock.lock()
        defer { closeLock.unlock() }
        if readyFired { return false }
        readyFired = true
        return true
    }

    /// Server-side: the connection is already accepted, so start the serving Tasks now (this drives the
    /// handshake to completion — a passive wait would stall it).
    func serveAsAccepted() {
        nwDiag("\(diagTag) serveAsAccepted (datagramsEnabled=\(datagramsEnabled))")
        if datagramsEnabled {
            ensureDatagramsTask()
        }
    }

    // --- lifecycle/state ---

    @objc public func onStateChanged(_ handler: @escaping (Int32, Int32, NSString?) -> Void) {
        stateHandler = handler
    }

    // --- datagrams ---

    private func ensureDatagramsTask() {
        guard datagramsEnabled else { return }
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        if datagramsTask == nil, let connection {
            nwDiag("\(diagTag) datagrams task: awaiting connection.datagrams")
            datagramsTask = Task { [weak self] in
                do {
                    let dg = try await connection.datagrams
                    nwDiag("\(self?.diagTag ?? "?") datagrams channel resolved max=\(dg.maximumDatagramSize)")
                    self?.storeDatagramsChannel(dg)
                    return dg
                } catch {
                    nwDiag("\(self?.diagTag ?? "?") datagrams channel FAILED: \(error)")
                    throw error
                }
            }
        }
    }

    private func storeDatagramsChannel(_ dg: QUIC.Datagrams<QUICDatagram>) {
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        datagramsChannel = dg
    }

    /// Resolve the datagram channel (creating it if needed) so `maxDatagramSize()` reflects the
    /// negotiated usable size before the first send. completion errCode != 0 ⇒ datagrams unavailable.
    @objc public func ensureDatagramsReady(_ completion: @escaping (Int32, NSString?) -> Void) {
        ensureDatagramsTask()
        guard let datagramsTask else {
            completion(-1, "datagrams not enabled on this connection" as NSString)
            return
        }
        Task {
            do {
                _ = try await datagramsTask.value
                completion(0, nil)
            } catch {
                completion(-1, "datagrams not ready: \(error)" as NSString)
            }
        }
    }

    @objc public func sendDatagram(_ data: NSData, completion: @escaping (Int32, NSString?) -> Void) {
        ensureDatagramsTask()
        guard let datagramsTask else {
            completion(-1, "datagrams not enabled on this connection" as NSString)
            return
        }
        let payload = data as Data
        Task {
            do {
                let dg = try await datagramsTask.value
                try await dg.send(payload)
                completion(0, nil)
            } catch {
                completion(-1, "datagram send failed: \(error)" as NSString)
            }
        }
    }

    /// Register a push handler for inbound datagrams; spins the serving Task that loops `datagrams.receive()`.
    @objc public func onDatagram(_ handler: @escaping (NSData) -> Void) {
        datagramHandler = handler
        ensureDatagramsTask()
        guard let datagramsTask else { return }
        let task = Task { [weak self] in
            do {
                let dg = try await datagramsTask.value
                while !Task.isCancelled {
                    let msg = try await dg.receive()
                    self?.datagramHandler?(msg.content as NSData)
                }
            } catch {
                // Connection gone / cancelled — end the loop; Kotlin observes close via onStateChanged.
            }
        }
        serveTasks.append(task)
    }

    @objc public func maxDatagramSize() -> Int32 {
        // The datagram CHANNEL's maximumDatagramSize is the negotiated usable size; the connection-level
        // usableDatagramFrameSize is always 0. 0 here means the channel hasn't resolved yet (call
        // ensureDatagramsReady first to guarantee a positive value before sending).
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        return Int32(truncatingIfNeeded: datagramsChannel?.maximumDatagramSize ?? 0)
    }

    // --- streams (validated in step 3; wired here) ---

    /// Open a locally-initiated stream. `completion` gets the wrapped stream + its real wire id, or an
    /// error (nil stream, non-zero errCode).
    @objc public func openStream(
        uni: Bool,
        completion: @escaping (NWQuic26Stream?, UInt64, Int32, NSString?) -> Void
    ) {
        guard let connection else {
            completion(nil, 0, -1, "connection not established" as NSString)
            return
        }
        let dir: QUICStream.Directionality = uni ? .unidirectional : .bidirectional
        Task { [weak self] in
            do {
                let s = try await connection.openStream(directionality: dir)
                nwDiag("\(self?.diagTag ?? "?") openStream uni=\(uni) -> id=\(s.streamID)")
                completion(NWQuic26Stream(stream: s), s.streamID, 0, nil)
            } catch {
                nwDiag("\(self?.diagTag ?? "?") openStream uni=\(uni) FAILED: \(error)")
                completion(nil, 0, -1, "openStream failed: \(error)" as NSString)
            }
        }
    }

    /// Register a push handler for peer-initiated streams (the new API's `inboundStreams { }` loop).
    @objc public func onInboundStream(_ handler: @escaping (NWQuic26Stream, UInt64, Bool, Bool) -> Void) {
        inboundStreamHandler = handler
        guard let connection else { return }
        let task = Task { [weak self] in
            nwDiag("\(self?.diagTag ?? "?") inboundStreams loop START")
            do {
                try await connection.inboundStreams { stream in
                    let isUni = stream.directionality == .unidirectional
                    let serverInit = stream.initiator == .server
                    nwDiag("\(self?.diagTag ?? "?") inboundStream FIRED id=\(stream.streamID) uni=\(isUni) serverInit=\(serverInit)")
                    // Deliver and RETURN immediately. The stream's lifetime is bound to the CONNECTION,
                    // not this handler closure (verified: a delivered stream reads fine after the handler
                    // returns), and `inboundStreams` invokes the handler SERIALLY — parking here to "keep
                    // the stream alive" would block every subsequent inbound stream (a multiplexing
                    // deadlock). The returned NWQuic26Stream retains the Swift stream for Kotlin.
                    self?.inboundStreamHandler?(NWQuic26Stream(stream: stream), stream.streamID, isUni, serverInit)
                }
                nwDiag("\(self?.diagTag ?? "?") inboundStreams loop ENDED (clean)")
            } catch {
                nwDiag("\(self?.diagTag ?? "?") inboundStreams loop ENDED err=\(error)")
                // Connection gone — end the accept loop.
            }
        }
        serveTasks.append(task)
    }

    // --- close ---

    @objc public func close(appErrorCode: UInt64) {
        closeLock.lock()
        let alreadyClosed = isClosed
        isClosed = true
        let cont = closedContinuation
        closedContinuation = nil
        closeLock.unlock()
        nwDiag("\(diagTag) close(appErrorCode=\(appErrorCode)) alreadyClosed=\(alreadyClosed)")
        if alreadyClosed { return }

        // The new NetworkConnection has no cancel() — teardown is via ARC. Snapshot the connection + its
        // serving Tasks; how/when we release them depends on whether this is a graceful close or an abort.
        let conn = connection
        let tasks = serveTasks
        let dgTask = datagramsTask
        serveTasks.removeAll()
        connection = nil
        datagramsTask = nil

        if let conn {
            if appErrorCode != 0 {
                // Application abort: stamp the application close code (CONNECTION_CLOSE, RFC 9000 §19.19)
                // and drop now — the app asked to tear down immediately. reason MUST be non-nil: NW's
                // nw_quic_set_application_error_reason calls strlen() on it, so a nil reason (the
                // ApplicationError(code:) default) crashes with SIGSEGV (strlen(NULL)). Pass "".
                conn.applicationError = NWProtocolQUIC.ApplicationError(code: appErrorCode, reason: "")
                for t in tasks { t.cancel() }
                dgTask?.cancel()
            } else {
                // Graceful close (code 0): a bare ARC drop here strands stream bytes NW has ACCEPTED
                // (`stream.send` completed) but not yet put on the wire — the peer then observes ENOTCONN
                // instead of the data, losing a just-written unidirectional stream (the PR #176 macos-26
                // loopback race, where `webTransport_clientOpensUniStream_serverReceives` writes then
                // immediately tears down). Hold the connection AND keep its receive loops RUNNING for a
                // bounded drain: on the flaky CI loopback a passively-held connection (no active I/O) has
                // its path dropped, so the peer sees it vanish (POSIX 57) before the last stream is
                // delivered — keeping it engaged lets NW keep the path up and flush the outstanding stream
                // data + FINs, THEN tear down. Detached, so close() still returns at once; the cap
                // (gracefulDrainNanos) guarantees a wedged drain can't leak the connection.
                let tag = diagTag
                Task {
                    nwDiag("\(tag) graceful drain START (keeping loops engaged)")
                    try? await Task.sleep(nanoseconds: NWQuic26Conn.gracefulDrainNanos)
                    nwDiag("\(tag) graceful drain END — releasing")
                    for t in tasks { t.cancel() }
                    dgTask?.cancel()
                    withExtendedLifetime(conn) {}
                }
            }
        } else {
            for t in tasks { t.cancel() }
            dgTask?.cancel()
        }
        cont?.resume()
    }

    /// Suspend until close() — used by the server run-closure to hold the connection open.
    func awaitClosed() async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            if !registerCloseContinuation(cont) { cont.resume() }
        }
    }

    /// Stash the close continuation (sync, so no lock crosses an await); false ⇒ already closed.
    private func registerCloseContinuation(_ cont: CheckedContinuation<Void, Never>) -> Bool {
        closeLock.lock()
        defer { closeLock.unlock() }
        if isClosed { return false }
        closedContinuation = cont
        return true
    }

    // --- pin evaluation (approach C: hash leaf DER, compare in Swift) ---

    func evaluatePin(trust: sec_trust_t, expectedHashes: [Data], requireChain: Bool) -> Bool {
        let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
              let leaf = chain.first
        else {
            pinFailureReason = 2 // no-peer-cert
            return false
        }
        let der = SecCertificateCopyData(leaf) as Data
        let digest = Data(SHA256.hash(data: der))
        let matched = expectedHashes.contains(digest)
        if !matched {
            pinFailureReason = 1 // mismatch
            pinComputedHashHex = digest.map { String(format: "%02x", $0) }.joined() as NSString
            pinMatchedLeafDer = nil
            return false
        }
        pinMatchedLeafDer = der as NSData
        if requireChain {
            // RequireBoth: the hash matched AND the chain must validate against system/anchor trust.
            var err: CFError?
            let chainOk = SecTrustEvaluateWithError(secTrust, &err)
            if !chainOk {
                pinFailureReason = 1
                return false
            }
        }
        return true
    }
}

@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
@objc public final class NWQuic26Stream: NSObject {
    // `var?` (not `let`) so reset() can drop our reference deterministically — the new Stream has no
    // cancel(), so RESET_STREAM/STOP_SENDING is emitted when the LAST reference deallocs. Stamping the
    // error code then nil-ing here (rather than waiting for the Kotlin wrapper to be GC'd) makes the
    // reset prompt. In-flight send/receive Tasks captured the stream locally, so they keep it alive
    // until they finish — a reset races them, which is the intended abort semantics.
    private var stream: QUIC.Stream<QUICStream>?
    private let cachedStreamId: UInt64

    @objc public var streamId: UInt64 { cachedStreamId }

    init(stream: QUIC.Stream<QUICStream>) {
        self.stream = stream
        self.cachedStreamId = stream.streamID
        super.init()
    }

    /// `completion(errCode, streamResetCode, desc)`: on failure, `streamResetCode` carries the peer's
    /// RESET_STREAM/STOP_SENDING application error code (so a stream-scoped abort is distinguishable from
    /// a connection close), or UInt64.max when the failure is not a stream reset.
    @objc public func send(
        _ data: NSData,
        endOfStream: Bool,
        completion: @escaping (Int32, UInt64, NSString?) -> Void
    ) {
        guard let stream else {
            completion(-1, UInt64.max, "stream is reset/closed" as NSString)
            return
        }
        let payload = data as Data
        Task {
            do {
                try await stream.send(payload, endOfStream: endOfStream)
                completion(0, UInt64.max, nil)
            } catch {
                // A peer STOP_SENDING/RESET_STREAM surfaces as a send error (POSIX 57) AND stamps the
                // stream's application error code; a connection close is POSIX 57 with no stream code (0).
                let rc = stream.streamApplicationErrorCode
                nwDiag("stream id=\(stream.streamID) send FAILED rc=\(rc) eos=\(endOfStream) err=\(error)")
                completion(-1, rc != 0 ? rc : UInt64.max, "stream send failed: \(error)" as NSString)
            }
        }
    }

    /// One-shot receive (Kotlin loops). `isEnd` is the FIN; `resetCode` carries the peer's RESET_STREAM
    /// application error (UInt64.max == not a reset); errCode != 0 is a transport error.
    @objc public func receive(
        maxBytes: Int32,
        completion: @escaping (NSData?, Bool, UInt64, Int32, NSString?) -> Void
    ) {
        guard let stream else {
            completion(nil, true, UInt64.max, 0, nil)
            return
        }
        Task {
            do {
                let msg = try await stream.receive(atLeast: 1, atMost: Int(maxBytes))
                let isEnd = msg.metadata.endOfStream
                completion(msg.content as NSData, isEnd, UInt64.max, 0, nil)
            } catch {
                // Distinguish a peer reset (carries a stream application error) from a graceful end /
                // transport error. The stream's application error code is set on a RESET_STREAM.
                let resetCode = stream.streamApplicationErrorCode
                if resetCode != 0 {
                    completion(nil, true, resetCode, 0, nil)
                } else {
                    completion(nil, true, UInt64.max, 0, nil)
                }
            }
        }
    }

    @objc public func reset(appErrorCode: UInt64) {
        // No cancel() on the new Stream — stamp the application error code, then drop our reference so
        // the stream deallocs and NW emits RESET_STREAM / STOP_SENDING (RFC 9000 §19.4/§19.5) promptly,
        // rather than waiting for the Kotlin wrapper to be garbage-collected.
        stream?.streamApplicationErrorCode = appErrorCode
        stream = nil
    }
}

@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
@objc public final class NWQuic26Listener: NSObject {
    private var listener: NetworkListener<QUIC>?
    private var runTask: Task<Void, Error>?

    func attach(listener: NetworkListener<QUIC>) {
        self.listener = listener
    }

    func attach(runTask: Task<Void, Error>) {
        self.runTask = runTask
    }

    @objc public func cancel() {
        // NetworkListener has no cancel() — cancelling the run Task ends `listener.run`, and dropping
        // the reference deinits the listener (ARC).
        runTask?.cancel()
        runTask = nil
        listener = nil
    }
}
