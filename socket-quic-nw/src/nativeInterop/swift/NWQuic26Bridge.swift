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
private func makeQuic(alpn: [String], config: NWQuic26Config) -> QUIC {
    // Flow-control windows + stream credits come from QuicOptions.flowControl (Kotlin). The new API
    // defaults these low/0, which STARVES a peer-opened stream and caps a bulk transfer at a tiny window
    // (spike gotcha), so the caller's values — not a hardcoded guess — must be applied (e.g. the
    // large-payload suite sets an 8 MB connection window so a 1 MB transfer cycling a 128 KB stream window
    // never wedges on the connection window). idle timeout is MILLISECONDS here (`.idleTimeout(30)` would
    // idle-kill at 30 ms — another spike gotcha).
    let flow = config.flowControl
    var q =
        QUIC(alpn: alpn)
            .idleTimeout(Int(config.idleTimeoutMs))
            // Cap the UDP payload (RFC 9000 max_udp_payload_size). NW otherwise uses the path MTU, which on
            // loopback is tens of KB, so one packet carries many KB — non-standard and inconsistent with the
            // quiche targets (which cap at 1350). Pinning the same value keeps packetization comparable.
            .maxUDPPayloadSize(Int(config.maxUdpPayloadSize))
            .initialMaxData(Int(flow.initialMaxData))
            .initialMaxStreamDataBidirectionalRemote(Int(flow.initialMaxStreamDataBidiRemote))
            .initialMaxStreamDataBidirectionalLocal(Int(flow.initialMaxStreamDataBidiLocal))
            .initialMaxStreamDataUnidirectional(Int(flow.initialMaxStreamDataUni))
            .initialMaxBidirectionalStreams(Int(flow.initialMaxStreamsBidi))
            .initialMaxUnidirectionalStreams(Int(flow.initialMaxStreamsUni))
    if config.datagramsEnabled {
        q = q.maxDatagramFrameSize(Int(config.maxDatagramFrameSize))
    }
    return q
}

@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
private extension QUIC {
    /// Install exactly one TLS verification strategy for a client, derived from [tls] (see
    /// [NWQuic26ClientTls]). The chosen validator publishes its outcome on [conn] as a single
    /// [NWQuic26Verdict] that Kotlin reads once establishment resolves.
    func withClientVerification(_ tls: NWQuic26ClientTls, conn: NWQuic26Conn) -> QUIC {
        if let pins = tls.serverCertificateHashes {
            // Leaf-hash pin validator (W3C serverCertificateHashes, approach C): SHA-256 the peer leaf DER
            // and compare in Swift. The pin IS the trust check, independent of verifyPeer; under RequireBoth
            // the chain must also validate (against the pinned CA anchors when supplied). Accepted carries
            // the matched leaf DER for the post-handshake W3C constraint check; rejections carry their reason.
            let requireChain = tls.requireChain
            let caAnchors = tls.trustedCaCertificateDers
            return self.tls.certificateValidator { _, trust in
                conn.evaluatePin(trust: trust, expectedHashes: pins, requireChain: requireChain, caAnchors: caAnchors)
            }
        }
        if let caAnchors = tls.trustedCaCertificateDers {
            // CA-anchor pinning (issue #81): validate the peer chain against these as the SOLE trust anchors.
            // Supplying anchors forces real peer verification regardless of verifyPeer (matches the legacy
            // nw_connection_group CA verify_block and the JVM/quiche path).
            return self.tls.certificateValidator { _, trust in
                conn.evaluateCaAnchors(trust: trust, anchorDers: caAnchors)
            }
        }
        if !tls.verifyPeer {
            // verifyPeer=false (insecure / loopback self-signed): accept any peer certificate. The new-API
            // analog of the legacy verify_block returning true; without it NW runs default system trust and
            // rejects a self-signed leaf.
            return self.tls.certificateValidator { _, _ in true }
        }
        return self // default NW system trust evaluation
    }
}

/// QUIC transport tuning forwarded from `QuicOptions`, grouped into one value carrier so `connect`/`listen`
/// take a single `config:` argument rather than a row of positional Int32s. Scalars are `Int32` (Kotlin
/// `Int`); `makeQuic` widens them to the Swift API's `Int`.
@objc public final class NWQuic26Config: NSObject {
    let idleTimeoutMs: Int32
    let maxUdpPayloadSize: Int32
    let maxDatagramFrameSize: Int32
    let keepAliveMs: Int32
    let flowControl: NWQuic26FlowControl

    var datagramsEnabled: Bool { maxDatagramFrameSize > 0 }

    @objc public init(
        idleTimeoutMs: Int32,
        maxUdpPayloadSize: Int32,
        maxDatagramFrameSize: Int32,
        keepAliveMs: Int32,
        flowControl: NWQuic26FlowControl
    ) {
        self.idleTimeoutMs = idleTimeoutMs
        self.maxUdpPayloadSize = maxUdpPayloadSize
        self.maxDatagramFrameSize = maxDatagramFrameSize
        self.keepAliveMs = keepAliveMs
        self.flowControl = flowControl
        super.init()
    }
}

/// Client-side TLS verification inputs forwarded from `QuicOptions`, grouped so `connect` takes one `tls:`
/// argument. Exactly one validator is installed from these (see `connect`): leaf-hash pinning when
/// [serverCertificateHashes] is set, else CA-anchor pinning when [trustedCaCertificateDers] is set, else
/// accept-any when [verifyPeer] is false, else default system trust.
@objc public final class NWQuic26ClientTls: NSObject {
    let serverCertificateHashes: [Data]?
    let trustedCaCertificateDers: [Data]?
    let requireChain: Bool
    let verifyPeer: Bool

    @objc public init(
        serverCertificateHashes: NSArray?,
        trustedCaCertificateDers: NSArray?,
        requireChain: Bool,
        verifyPeer: Bool
    ) {
        self.serverCertificateHashes = (serverCertificateHashes as? [NSData])?.map { $0 as Data }
        self.trustedCaCertificateDers = (trustedCaCertificateDers as? [NSData])?.map { $0 as Data }
        self.requireChain = requireChain
        self.verifyPeer = verifyPeer
        super.init()
    }
}

/// QUIC flow-control limits forwarded from `QuicOptions.flowControl`. A plain value carrier so the
/// `connect`/`listen` selectors don't grow six positional Int parameters each.
@objc public final class NWQuic26FlowControl: NSObject {
    // Int64 (not Swift's platform-width Int) so Kotlin/Native sees a uniform `Long` across every Apple
    // target — a width-varying `Int` is rejected in shared appleMain signatures. makeQuic narrows to `Int`.
    let initialMaxData: Int64
    let initialMaxStreamDataBidiLocal: Int64
    let initialMaxStreamDataBidiRemote: Int64
    let initialMaxStreamDataUni: Int64
    let initialMaxStreamsBidi: Int64
    let initialMaxStreamsUni: Int64

    @objc public init(
        initialMaxData: Int64,
        initialMaxStreamDataBidiLocal: Int64,
        initialMaxStreamDataBidiRemote: Int64,
        initialMaxStreamDataUni: Int64,
        initialMaxStreamsBidi: Int64,
        initialMaxStreamsUni: Int64
    ) {
        self.initialMaxData = initialMaxData
        self.initialMaxStreamDataBidiLocal = initialMaxStreamDataBidiLocal
        self.initialMaxStreamDataBidiRemote = initialMaxStreamDataBidiRemote
        self.initialMaxStreamDataUni = initialMaxStreamDataUni
        self.initialMaxStreamsBidi = initialMaxStreamsBidi
        self.initialMaxStreamsUni = initialMaxStreamsUni
        super.init()
    }
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
    case .wifiAware(let code): return Int(code)
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
        config: NWQuic26Config,
        tls: NWQuic26ClientTls,
        onReady: @escaping (Int32, NSString?) -> Void
    ) -> NWQuic26Conn {
        let alpnList = (alpn as? [String]) ?? []

        let conn = NWQuic26Conn(datagramsEnabled: config.datagramsEnabled, isServer: false, keepAliveMs: Int(config.keepAliveMs))

        let quic = makeQuic(alpn: alpnList, config: config).withClientVerification(tls, conn: conn)

        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host as String),
            port: NWEndpoint.Port(rawValue: port) ?? .any
        )
        let connection = NetworkConnection(to: endpoint) { quic }
        conn.attach(connection: connection, onReady: onReady)
        nwDiag("\(conn.diagTag) connect() -> \(host):\(port) dg=\(config.datagramsEnabled) alpn=\(alpnList)")
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
        config: NWQuic26Config,
        onConnection: @escaping (NWQuic26Conn) -> Void,
        onListenerState: @escaping (Int32, UInt16, NSString?) -> Void
    ) -> NWQuic26Listener {
        let alpnList = (alpn as? [String]) ?? []
        let listenerBox = NWQuic26Listener()

        guard let identity = loadIdentity(path: p12Path as String, password: p12Password as String) else {
            onListenerState(-1, 0, "Failed to import PKCS#12 identity from \(p12Path)" as NSString)
            return listenerBox
        }

        let quic = makeQuic(alpn: alpnList, config: config).tls.localIdentity(identity)

        let datagramsEnabled = config.datagramsEnabled
        let keepAliveMsInt = Int(config.keepAliveMs)

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
    // Verify the CF type before the cast: a malformed/empty p12 can yield an unexpected object under this
    // key, and an unconditional `as! SecIdentity` would trap the process. Fail closed (nil → the caller
    // reports an identity-import failure) instead of crashing.
    guard CFGetTypeID(raw as CFTypeRef) == SecIdentityGetTypeID() else { return nil }
    return sec_identity_create(raw as! SecIdentity)
}

// --- TLS verify verdict (issue #81 / #173) ---
// A single published, closed outcome of the client's certificate verification, consumed by Kotlin with
// an exhaustive `when`. This replaces the former grab-bag of mutable out-param fields (a sentinel
// `pinFailureReason` int + `pinComputedHashHex` + `pinMatchedLeafDer`), which allowed impossible
// combinations and was read across the verify-queue→connect-coroutine boundary as several independent
// fields. The verdict is created once, at the moment of decision, and carries exactly the data its case
// needs — nothing more is representable.

/// Base type for the four possible certificate-verification outcomes (see the subclasses). K/N imports
/// this hierarchy so Kotlin can `is`-match each case exhaustively.
@objc public class NWQuic26Verdict: NSObject {}

/// The peer certificate was accepted. [leafDer] is the matched leaf's DER when acceptance came from a
/// leaf-hash pin (so Kotlin can run the W3C leaf constraints post-handshake), otherwise nil (CA-anchor
/// trust or verifyPeer=false — neither has follow-up leaf constraints).
@objc public final class NWQuic26VerdictAccepted: NWQuic26Verdict {
    @objc public let leafDer: NSData?
    init(leafDer: NSData?) {
        self.leafDer = leafDer
        super.init()
    }
}

/// A leaf certificate was presented and hashed, but its SHA-256 matched none of the pins.
/// [computedHashHex] is the lowercase-hex digest of the leaf the server actually presented.
@objc public final class NWQuic26VerdictHashMismatch: NWQuic26Verdict {
    @objc public let computedHashHex: NSString
    init(computedHashHex: NSString) {
        self.computedHashHex = computedHashHex
        super.init()
    }
}

/// The peer presented no leaf certificate to match against the pins.
@objc public final class NWQuic26VerdictNoPeerCertificate: NWQuic26Verdict {}

/// Trust-chain evaluation rejected the peer: CA-anchor pinning failed, or RequireBoth chain validation
/// failed. Maps to a connection-level QuicCloseException (parity with the legacy cancelled-group path).
@objc public final class NWQuic26VerdictTrustRejected: NWQuic26Verdict {}

@available(macOS 26.0, iOS 26.0, tvOS 26.0, watchOS 26.0, *)
@objc public final class NWQuic26Conn: NSObject {
    private let datagramsEnabled: Bool
    private let isServer: Bool
    private let keepAliveMs: Int

    // The underlying connection. Set once in attach(), nil'd in close(), and read from the setup paths
    // (openStream / onInboundStream / ensureDatagramsTask / applyKeepAlive). Reading an optional class
    // reference on one thread while close() writes nil on another races the ARC retain/release on the
    // NetworkConnection object — a refcount corruption that can crash. Funnel get/set through
    // connectionLock so the retain a reader takes is serialized against close()'s release.
    private let connectionLock = NSLock()
    private var connection: NetworkConnection<QUIC>?

    private func currentConnection() -> NetworkConnection<QUIC>? {
        connectionLock.lock()
        defer { connectionLock.unlock() }
        return connection
    }

    private func setConnection(_ c: NetworkConnection<QUIC>?) {
        connectionLock.lock()
        defer { connectionLock.unlock() }
        connection = c
    }

    private func currentDatagramsTask() -> Task<QUIC.Datagrams<QUICDatagram>, Error>? {
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        return datagramsTask
    }

    private func setDatagramsTask(_ t: Task<QUIC.Datagrams<QUICDatagram>, Error>?) {
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        datagramsTask = t
    }

    // Datagram channel resolved once (obtaining it drives the handshake). Both sendDatagram and the
    // receive loop await this Task's value. The resolved channel is also cached so the synchronous
    // maxDatagramSize() can read its `maximumDatagramSize` (the connection-level `usableDatagramFrameSize`
    // is always 0 — wrong accessor; the channel's value is the real negotiated usable size).
    private var datagramsTask: Task<QUIC.Datagrams<QUICDatagram>, Error>?
    private var datagramsChannel: QUIC.Datagrams<QUICDatagram>?
    private let datagramsLock = NSLock()

    // How connection-state events reach Kotlin. `.pending` means Kotlin hasn't wired its handler yet — and
    // remembers whether a verify rejection raced ahead, so the handler receives it the instant it's wired.
    // `.wired` carries the handler. Modeling it as one value (rather than a `handler?` + a separate
    // `pendingVerifyRejection` bool) makes the impossible "handler set AND a pending flag still latched"
    // state unrepresentable, and keeps the rejection-stash inseparable from the not-yet-wired case.
    private enum StateDelivery {
        case pending(verifyRejected: Bool)
        case wired(StateHandler)
    }
    private typealias StateHandler = (Int32, Int32, NSString?) -> Void
    private let stateLock = NSLock()
    private var stateDelivery: StateDelivery = .pending(verifyRejected: false)

    // Peer-event serving Tasks (datagram receive loop + inbound-stream accept loop). Appended from the
    // Kotlin setup coroutines (onDatagram/onInboundStream, on Dispatchers.Default — i.e. arbitrary
    // threads) and snapshot+cleared by close() (another thread). A Swift Array is NOT thread-safe, so an
    // append racing the close()-time removeAll() corrupts the array → an intermittent native crash
    // (SIGSEGV/SIGABRT) that only the virtualized CI runner's timing surfaces. Funnel every access through
    // serveTasksLock; serveTasksClosed makes a post-close append a no-op (the task is cancelled instead of
    // leaked, since close() already drained).
    private let serveTasksLock = NSLock()
    private var serveTasks: [Task<Void, Never>] = []
    private var serveTasksClosed = false

    /// Register a serving Task under the lock. If close() already drained, cancel the task instead of
    /// appending it (the connection is gone — its loop would only fault).
    private func addServeTask(_ task: Task<Void, Never>) {
        serveTasksLock.lock()
        if serveTasksClosed {
            serveTasksLock.unlock()
            task.cancel()
            return
        }
        serveTasks.append(task)
        serveTasksLock.unlock()
    }

    /// Snapshot + clear the serving Tasks and latch closed (so later addServeTask cancels rather than
    /// appends). Called once by close().
    private func drainServeTasks() -> [Task<Void, Never>] {
        serveTasksLock.lock()
        defer { serveTasksLock.unlock() }
        serveTasksClosed = true
        let snapshot = serveTasks
        serveTasks.removeAll()
        return snapshot
    }

    // Locally-opened streams, retained for the connection's lifetime. The new `QUIC.Stream` emits
    // RESET_STREAM when its LAST reference deallocs (that's how reset() works), so without an owner here a
    // stream whose only reference is the Kotlin wrapper is RESET the moment Kotlin garbage-collects that
    // wrapper — dropping any send bytes NW has accepted (`stream.send` returns once data is BUFFERED, not
    // flushed) but not yet put on the wire, truncating a bulk transfer mid-flight. The new API binds a
    // stream's lifetime to its connection (see the inboundStreams note — NW retains inbound streams the
    // same way), so we mirror that for opened streams: hold them until close() releases them (after the
    // graceful drain, so the final flush completes).
    private var openedStreams: [NWQuic26Stream] = []
    private let openedStreamsLock = NSLock()

    private func retainOpenedStream(_ stream: NWQuic26Stream) {
        openedStreamsLock.lock()
        defer { openedStreamsLock.unlock() }
        openedStreams.append(stream)
    }

    private func drainOpenedStreams() -> [NWQuic26Stream] {
        openedStreamsLock.lock()
        defer { openedStreamsLock.unlock() }
        let snapshot = openedStreams
        openedStreams.removeAll()
        return snapshot
    }

    // The connection's progress through its lifecycle, one-directional: `handshaking` until the first
    // terminal state resolves the connect (firing onReady exactly once), then `settled` while it's live,
    // then `closed` once close() runs (which may also fire straight from `handshaking` for a connect torn
    // down before it readies). One enum replaces the former readyFired/isClosed booleans, which could
    // encode the impossible "closed yet onReady never claimed" and were read across separate methods.
    private enum Lifecycle { case handshaking, settled, closed }
    private let lifecycleLock = NSLock()
    private var lifecycle: Lifecycle = .handshaking
    // Resumed by close(), unblocking the server run-closure (whose lifetime == connection lifetime).
    private var closedContinuation: CheckedContinuation<Void, Never>?

    // The single published TLS verify outcome (issue #81). nil means no custom verification ran, or it
    // accepted generically (CA-anchor trust / verifyPeer=false / default system trust — none of which
    // need a follow-up). Set exactly once by the verify validator below; read by Kotlin once
    // establishment resolves. See NWQuic26Verdict and publishVerdict.
    private let verdictLock = NSLock()
    @objc public private(set) var verdict: NWQuic26Verdict?

    private func publishVerdict(_ v: NWQuic26Verdict) {
        verdictLock.lock()
        defer { verdictLock.unlock() }
        if verdict == nil { verdict = v }
    }

    // Stable short tag for QUIC_NW_DIAG traces, e.g. "srv#3" / "cli#4".
    private let connSeq = nwDiagNextSeq()
    var diagTag: String { "\(isServer ? "srv" : "cli")#\(connSeq)" }

    // Graceful-close drain window: how long a clean close() holds the connection alive so NW can flush
    // stream bytes it has accepted but not yet put on the wire (see close()). Bounded so a wedged flush
    // can't leak the connection; runs detached so close() still returns immediately.
    private static let gracefulDrainNanos: UInt64 = 500_000_000

    // Serializes the FINAL ARC release of every NWConnection (and the streams held alive for its flush)
    // across the whole process. The new Network.framework connection has no explicit cancel(): teardown
    // runs when the last strong reference deallocs, which drives NW's internal dispatch teardown. On the
    // virtualized macos-26 CI runner, two NWConnection deallocs running at once SIGSEGV inside that NW
    // teardown — and they DO collide, because connSeq is process-global and an earlier connection's
    // detached 500 ms graceful-drain Task routinely finishes releasing just as a later connection closes
    // (proven in a QUIC_NW_DIAG trace: "cli#1/srv#2 graceful drain END — releasing" interleaved with a
    // later "cli#15 close", then exitValue=139 / SIGSEGV). The e2d4c3a locks synchronized the lifecycle
    // STATE snapshots but not the dealloc itself. Funnelling the release onto one serial queue makes the
    // deallocs strictly one-at-a-time without changing their timing — each connection still drops at the
    // moment its owner intended, just never overlapping another's. Off the Swift concurrency pool too, so
    // NW's teardown work doesn't block a cooperative thread.
    private static let teardownQueue = DispatchQueue(label: "com.ditchoom.nwquic26.teardown")

    /// Drop the last strong references to `conn` (the streams held for its flush, AND the resolved datagram
    /// channel) on the shared serial [teardownQueue], so this NWConnection's dealloc never overlaps
    /// another's (which SIGSEGVs inside NW on the CI runner). The caller cancels the serving Tasks first;
    /// this governs only the ARC release that triggers NW teardown. The queue closure holds the last
    /// reference, so the actual dealloc runs on the serial queue regardless of which thread enqueued it.
    ///
    /// `datagrams` is load-bearing on the datagram-enabled path: the resolved `QUIC.Datagrams` channel
    /// RETAINS its parent NWConnection (it's a member channel of that connection). Held only on
    /// `self.datagramsChannel`, that reference outlived `withExtendedLifetime(conn)` — so `conn` was NOT
    /// the last reference, and the NWConnection's actual dealloc ran later, when NWQuic26Conn itself
    /// deallocced (Kotlin-GC-driven, OFF this queue), free to race another connection's teardown → the
    /// macos-26 SIGSEGV that reproduced only on the datagram-enabled coexistence test. Funnelling the
    /// channel's final release through the same queue closure makes that NWConnection dealloc serial too.
    private static func releaseSerially(
        _ conn: NetworkConnection<QUIC>?,
        streams: [NWQuic26Stream],
        datagrams: QUIC.Datagrams<QUICDatagram>?
    ) {
        teardownQueue.async {
            withExtendedLifetime(streams) {}
            withExtendedLifetime(datagrams) {}
            withExtendedLifetime(conn) {}
        }
    }

    init(datagramsEnabled: Bool, isServer: Bool, keepAliveMs: Int) {
        self.datagramsEnabled = datagramsEnabled
        self.isServer = isServer
        self.keepAliveMs = keepAliveMs
        super.init()
    }

    /// Wire the underlying connection + fire `onReady` exactly once on the first terminal state.
    func attach(connection: NetworkConnection<QUIC>, onReady: @escaping (Int32, NSString?) -> Void) {
        setConnection(connection)
        connection.onStateUpdate { [weak self] _, state in
            let (code, errCode, desc) = mapConnState(state)
            nwDiag("\(self?.diagTag ?? "?") state=\(code) (\(state)) errCode=\(errCode)")
            switch state {
            case .ready:
                self?.applyKeepAlive()
                if self?.claimSettled() == true { onReady(0, nil) }
            case .failed, .cancelled:
                if self?.claimSettled() == true {
                    onReady(errCode == 0 ? -1 : errCode, (desc ?? "connection \(state)") as NSString)
                }
            default:
                // .setup / .preparing / .waiting are NON-terminal. In particular `.waiting` carrying a
                // transient POSIX 50 (ENETDOWN) fires mid-handshake on loopback and RECOVERS to `.ready`
                // — so it must NOT resolve onReady (doing so killed the connect before it readied). The
                // only terminal outcomes for connect are .ready / .failed / .cancelled.
                break
            }
            self?.deliverState(code, errCode, desc as NSString?)
        }
    }

    /// Claim the one-shot connect resolution (`handshaking` → `settled`), firing onReady. Returns true
    /// exactly once — and never once close() has won, so a late `.ready` after close can't fire onReady on
    /// a dead connection.
    private func claimSettled() -> Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        guard lifecycle == .handshaking else { return false }
        lifecycle = .settled
        return true
    }

    /// Apply the configured QUIC keepalive interval once the connection is live (RFC 9000 §10.1.2). The
    /// new API exposes keepalive as a settable connection property (`KeepAliveBehavior`) — the analog of
    /// the legacy path's `nw_quic_set_keepalive_interval` on a started flow's metadata, which is why it's
    /// applied here on `.ready` rather than on the create-time options. `.seconds` takes whole seconds, so
    /// a sub-second interval is clamped up to 1s; keepAliveMs == 0 (disabled) is a no-op. Idempotent across
    /// repeated `.ready` deliveries. (Issue #130 keepalive parity.)
    private func applyKeepAlive() {
        guard keepAliveMs > 0, let connection = currentConnection() else { return }
        connection.keepalive = .seconds(max(1, keepAliveMs / 1000))
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
        stateLock.lock()
        // Carry forward a verify rejection that raced ahead of this wiring (delivered below).
        let deliverRejection: Bool
        if case .pending(let rejected) = stateDelivery { deliverRejection = rejected } else { deliverRejection = false }
        stateDelivery = .wired(handler)
        stateLock.unlock()
        if deliverRejection { handler(4, 0, "certificate verification rejected" as NSString) }
    }

    /// Forward a connection state change to Kotlin, if its handler is wired yet (early states can arrive
    /// before `onStateChanged`; Kotlin re-reads the terminal state when it wires up).
    private func deliverState(_ code: Int32, _ errCode: Int32, _ desc: NSString?) {
        stateLock.lock()
        let handler: StateHandler? = { if case .wired(let h) = stateDelivery { return h } else { return nil } }()
        stateLock.unlock()
        handler?(code, errCode, desc)
    }

    /// The TLS verify validator rejected the peer. Network.framework does NOT report a client verify
    /// rejection as a connection state change — the handshake just stalls (the same gotcha the legacy
    /// nw_connection_group path cancels the group to work around). So drive a synthetic terminal (state
    /// 4 = failed) to Kotlin, which reads the already-published [verdict] to throw the typed exception. If
    /// Kotlin hasn't wired its handler yet, stash the rejection in `.pending` so wiring delivers it.
    private func signalVerifyRejected() {
        stateLock.lock()
        let target: StateHandler?
        switch stateDelivery {
        case .wired(let h): target = h
        case .pending: stateDelivery = .pending(verifyRejected: true); target = nil
        }
        stateLock.unlock()
        target?(4, 0, "certificate verification rejected" as NSString)
    }

    /// Publish [v] as the (single) verify outcome and drive the synthetic terminal; returns false so the
    /// validator closure rejects the handshake.
    private func rejectWith(_ v: NWQuic26Verdict) -> Bool {
        publishVerdict(v)
        signalVerifyRejected()
        return false
    }

    // --- datagrams ---

    private func ensureDatagramsTask() {
        guard datagramsEnabled else { return }
        // Snapshot the connection BEFORE taking datagramsLock — never nest connectionLock inside
        // datagramsLock (close() takes them sequentially, so a reversed nesting here could deadlock).
        let conn = currentConnection()
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        if datagramsTask == nil, let connection = conn {
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

    /// Snapshot AND clear the resolved datagram channel under datagramsLock. close() calls this so the
    /// channel's (and thus its parent NWConnection's) final release is handed to the serial teardownQueue
    /// via releaseSerially, rather than left on `self.datagramsChannel` to dealloc off-queue when
    /// NWQuic26Conn itself is GC'd (the macos-26 datagram-path SIGSEGV). Nil'ing it here also stops a
    /// late storeDatagramsChannel from re-stranding the reference. Mirrors the connectionLock /
    /// serveTasksLock snapshot-and-clear discipline; never nests another lock inside datagramsLock.
    private func takeDatagramsChannel() -> QUIC.Datagrams<QUICDatagram>? {
        datagramsLock.lock()
        defer { datagramsLock.unlock() }
        let dg = datagramsChannel
        datagramsChannel = nil
        return dg
    }

    /// Resolve the datagram channel (creating it if needed) so `maxDatagramSize()` reflects the
    /// negotiated usable size before the first send. completion errCode != 0 ⇒ datagrams unavailable.
    @objc public func ensureDatagramsReady(_ completion: @escaping (Int32, NSString?) -> Void) {
        ensureDatagramsTask()
        guard let datagramsTask = currentDatagramsTask() else {
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
        guard let datagramsTask = currentDatagramsTask() else {
            completion(-1, "datagrams not enabled on this connection" as NSString)
            return
        }
        let payload = data as Data
        let tag = diagTag
        Task {
            do {
                let dg = try await datagramsTask.value
                nwDiag("\(tag) sendDatagram: \(payload.count)B -> dg.send")
                try await dg.send(payload)
                nwDiag("\(tag) sendDatagram: \(payload.count)B SENT")
                completion(0, nil)
            } catch {
                nwDiag("\(tag) sendDatagram: \(payload.count)B FAILED: \(error)")
                completion(-1, "datagram send failed: \(error)" as NSString)
            }
        }
    }

    /// Register a push handler for inbound datagrams; spins the serving Task that loops `datagrams.receive()`.
    @objc public func onDatagram(_ handler: @escaping (NSData) -> Void) {
        ensureDatagramsTask()
        guard let datagramsTask = currentDatagramsTask() else { return }
        // Capture `handler` + `tag` + the task directly (not via self) so the serving Task holds no
        // reference to this connection — it ends when the datagram channel closes, or close() cancels it.
        let tag = diagTag
        let task = Task {
            do {
                let dg = try await datagramsTask.value
                // The moment the receive loop is actually pending on dg.receive(). Compared against the
                // peer's `sendDatagram` timestamp this pins the setup-ordering race: a datagram that
                // arrives before this point has no app-level NW backlog to land in and is dropped (the
                // suspected macos-26 CI-only datagramRoundTrip flake — see issue notes). Logged once.
                nwDiag("\(tag) datagram recv loop ARMED (awaiting dg.receive)")
                while !Task.isCancelled {
                    let msg = try await dg.receive()
                    nwDiag("\(tag) datagram RECEIVED \(msg.content.count)B")
                    handler(msg.content as NSData)
                }
            } catch {
                nwDiag("\(tag) datagram recv loop ENDED: \(error)")
                // Connection gone / cancelled — end the loop; Kotlin observes close via onStateChanged.
            }
        }
        addServeTask(task)
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
        guard let connection = currentConnection() else {
            completion(nil, 0, -1, "connection not established" as NSString)
            return
        }
        let dir: QUICStream.Directionality = uni ? .unidirectional : .bidirectional
        Task { [weak self] in
            do {
                let s = try await connection.openStream(directionality: dir)
                nwDiag("\(self?.diagTag ?? "?") openStream uni=\(uni) -> id=\(s.streamID)")
                let wrapped = NWQuic26Stream(stream: s)
                // Retain so a GC of the Kotlin wrapper can't dealloc-then-RESET the stream mid-flush.
                self?.retainOpenedStream(wrapped)
                completion(wrapped, s.streamID, 0, nil)
            } catch {
                nwDiag("\(self?.diagTag ?? "?") openStream uni=\(uni) FAILED: \(error)")
                completion(nil, 0, -1, "openStream failed: \(error)" as NSString)
            }
        }
    }

    /// Register a push handler for peer-initiated streams (the new API's `inboundStreams { }` loop).
    @objc public func onInboundStream(_ handler: @escaping (NWQuic26Stream, UInt64, Bool, Bool) -> Void) {
        guard let connection = currentConnection() else { return }
        // Capture `handler` + `tag` directly (not via self) so the serving Task — and the @Sendable
        // `inboundStreams` closure it nests — hold no reference to this connection.
        let tag = diagTag
        let task = Task {
            nwDiag("\(tag) inboundStreams loop START")
            do {
                try await connection.inboundStreams { stream in
                    let isUni = stream.directionality == .unidirectional
                    let serverInit = stream.initiator == .server
                    nwDiag("\(tag) inboundStream FIRED id=\(stream.streamID) uni=\(isUni) serverInit=\(serverInit)")
                    // Deliver and RETURN immediately. The stream's lifetime is bound to the CONNECTION,
                    // not this handler closure (verified: a delivered stream reads fine after the handler
                    // returns), and `inboundStreams` invokes the handler SERIALLY — parking here to "keep
                    // the stream alive" would block every subsequent inbound stream (a multiplexing
                    // deadlock). The returned NWQuic26Stream retains the Swift stream for Kotlin.
                    handler(NWQuic26Stream(stream: stream), stream.streamID, isUni, serverInit)
                }
                nwDiag("\(tag) inboundStreams loop ENDED (clean)")
            } catch {
                nwDiag("\(tag) inboundStreams loop ENDED err=\(error)")
                // Connection gone — end the accept loop.
            }
        }
        addServeTask(task)
    }

    // --- close ---

    @objc public func close(appErrorCode: UInt64) {
        lifecycleLock.lock()
        let alreadyClosed = lifecycle == .closed
        lifecycle = .closed
        let cont = closedContinuation
        closedContinuation = nil
        lifecycleLock.unlock()
        nwDiag("\(diagTag) close(appErrorCode=\(appErrorCode)) alreadyClosed=\(alreadyClosed)")
        if alreadyClosed { return }

        // The new NetworkConnection has no cancel() — teardown is via ARC. Snapshot the connection + its
        // serving Tasks; how/when we release them depends on whether this is a graceful close or an abort.
        // All three snapshot+clears go through the same locks their readers use (connectionLock /
        // serveTasksLock / datagramsLock), so a setup path running concurrently with this teardown can't
        // tear an array or race the connection's refcount.
        let conn = currentConnection()
        let tasks = drainServeTasks()
        let dgTask = currentDatagramsTask()
        // Snapshot the retained opened streams: an abort releases them now (RESET); a graceful close holds
        // them through the drain so their buffered bytes flush before they dealloc.
        let streams = drainOpenedStreams()
        setConnection(nil)
        setDatagramsTask(nil)

        if let conn {
            if appErrorCode != 0 {
                // Application abort: stamp the application close code (CONNECTION_CLOSE, RFC 9000 §19.19)
                // and drop now — the app asked to tear down immediately. reason MUST be non-nil: NW's
                // nw_quic_set_application_error_reason calls strlen() on it, so a nil reason (the
                // ApplicationError(code:) default) crashes with SIGSEGV (strlen(NULL)). Pass "".
                conn.applicationError = NWProtocolQUIC.ApplicationError(code: appErrorCode, reason: "")
                for t in tasks { t.cancel() }
                dgTask?.cancel()
                // Abort wants the streams (RESET) + connection gone now — but still serialize the dealloc
                // so it can't overlap another connection's teardown and SIGSEGV inside NW. Take the
                // resolved datagram channel too: it retains the NWConnection, so without funnelling it
                // through the queue the connection's real dealloc would run off-queue later (see
                // releaseSerially / takeDatagramsChannel).
                let dgChannel = takeDatagramsChannel()
                NWQuic26Conn.releaseSerially(conn, streams: streams, datagrams: dgChannel)
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
                //
                // The hold is NOT only for flushing opened-stream bytes — it also keeps the NW path up long
                // enough for the PEER to finalize (e.g. a server that closes right after the handshake; the
                // client otherwise sees POSIX 57 "Socket is not connected" before it observes the verdict).
                // So it runs for EVERY graceful close, even a stream-less one. (An earlier attempt to skip it
                // when `streams` was empty broke AppleQuicCertificateHashPinningTests, which closes the server
                // immediately after the pinned handshake with no streams open.)
                let tag = diagTag
                Task {
                    nwDiag("\(tag) graceful drain START (keeping loops engaged)")
                    try? await Task.sleep(nanoseconds: NWQuic26Conn.gracefulDrainNanos)
                    nwDiag("\(tag) graceful drain END — releasing")
                    for t in tasks { t.cancel() }
                    dgTask?.cancel()
                    // Await the serving loops' actual unwind before releasing the connection. Each serving
                    // Task (the `inboundStreams` loop) captures the NW connection STRONGLY, and cancellation
                    // is cooperative — the Task drops its ref only when it finishes unwinding, on a Swift
                    // concurrency thread. If we released `conn` without waiting, that async drop could be the
                    // LAST reference and run the dealloc off the serial teardown queue, racing another
                    // connection's dealloc — the exact SIGSEGV we're fixing. Awaiting here makes the serial
                    // queue's closure the guaranteed last-ref holder. Cancelled inboundStreams loops end
                    // promptly (traces show "loop ENDED ... CancellationError"), so this can't wedge.
                    for t in tasks { _ = await t.value }
                    if let dgTask { _ = try? await dgTask.value }
                    // Awaiting dgTask above guarantees its storeDatagramsChannel ran, so the resolved
                    // datagram channel is now on self.datagramsChannel. Take it (clearing self's reference)
                    // here, AFTER the await, so a still-resolving task can't re-strand it. self is captured
                    // strongly by this drain Task, which keeps NWQuic26Conn alive through the drain — the
                    // whole point: it stops self.deinit from releasing datagramsChannel (and thus the
                    // NWConnection it retains) off-queue before this runs.
                    let dgChannel = self.takeDatagramsChannel()
                    // The streams + datagram channel were held alive THROUGH the drain (releasing the
                    // streams at close() would dealloc-then-RESET them and truncate the very flush this
                    // drain exists to complete); now hand the final release of the streams, the datagram
                    // channel, AND the connection to the serial teardown queue, so this NWConnection's
                    // dealloc can't overlap another connection's and SIGSEGV.
                    NWQuic26Conn.releaseSerially(conn, streams: streams, datagrams: dgChannel)
                }
            }
        } else {
            for t in tasks { t.cancel() }
            dgTask?.cancel()
            _ = streams
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
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        if lifecycle == .closed { return false }
        closedContinuation = cont
        return true
    }

    // --- certificate verification (approach C: hash leaf DER / evaluate chain in Swift, publish a verdict) ---

    /// Leaf-hash pinning. Publishes [NWQuic26VerdictAccepted] (carrying the matched leaf DER) on success,
    /// or a typed rejection verdict on failure, and returns whether to accept the handshake.
    func evaluatePin(trust: sec_trust_t, expectedHashes: [Data], requireChain: Bool, caAnchors: [Data]?) -> Bool {
        nwDiag("\(diagTag) evaluatePin pins=\(expectedHashes.count) requireChain=\(requireChain)")
        let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
              let leaf = chain.first
        else {
            return rejectWith(NWQuic26VerdictNoPeerCertificate())
        }
        let der = SecCertificateCopyData(leaf) as Data
        let digest = Data(SHA256.hash(data: der))
        if !expectedHashes.contains(digest) {
            let hex = digest.map { String(format: "%02x", $0) }.joined()
            nwDiag("\(diagTag) evaluatePin HASH MISMATCH computed=\(hex.prefix(16))… -> reject")
            return rejectWith(NWQuic26VerdictHashMismatch(computedHashHex: hex as NSString))
        }
        if requireChain {
            // RequireBoth defense in depth: the hash matched AND the chain must validate. Pin the supplied
            // CA anchors as the sole anchors when present, else default system trust. A chain failure here
            // is a generic trust rejection (NOT a hash mismatch) — mirrors quiche / the legacy verify_block.
            if let caAnchors, !caAnchors.isEmpty { _ = applyAnchors(caAnchors, to: secTrust) }
            var err: CFError?
            if !SecTrustEvaluateWithError(secTrust, &err) {
                return rejectWith(NWQuic26VerdictTrustRejected())
            }
        }
        publishVerdict(NWQuic26VerdictAccepted(leafDer: der as NSData))
        return true
    }

    /// CA-anchor pinning (issue #81 — the new-API analog of nw_quic_helpers.h's CA verify_block):
    /// validate the peer's chain against [anchorDers] as the SOLE trust anchors (no network fetch).
    /// Acceptance leaves [verdict] nil (no leaf-hash constraints follow); rejection publishes TrustRejected.
    func evaluateCaAnchors(trust: sec_trust_t, anchorDers: [Data]) -> Bool {
        let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
        if !applyAnchors(anchorDers, to: secTrust) {
            return rejectWith(NWQuic26VerdictTrustRejected())
        }
        var err: CFError?
        if !SecTrustEvaluateWithError(secTrust, &err) {
            return rejectWith(NWQuic26VerdictTrustRejected())
        }
        return true
    }

    /// Pin [ders] as the sole trust anchors on [secTrust], disabling network fetch. Returns false if none
    /// parsed (so the caller fails closed). Shared by [evaluateCaAnchors] and the RequireBoth branch of
    /// [evaluatePin].
    @discardableResult
    private func applyAnchors(_ ders: [Data], to secTrust: SecTrust) -> Bool {
        let anchors = ders.compactMap { SecCertificateCreateWithData(nil, $0 as CFData) }
        if anchors.isEmpty { return false }
        SecTrustSetAnchorCertificates(secTrust, anchors as CFArray)
        SecTrustSetAnchorCertificatesOnly(secTrust, true)
        SecTrustSetNetworkFetchAllowed(secTrust, false)
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
