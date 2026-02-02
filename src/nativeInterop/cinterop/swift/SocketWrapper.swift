//
//  SocketWrapper.swift
//  Zero-copy Network.framework wrapper for Kotlin/Native
//
//  Created by DitchOoM
//

import Foundation
import Network

// MARK: - Error Types

/// Socket error classification for Kotlin interop
@objc public enum SocketErrorType: Int {
    case none = 0
    case posix = 1
    case dns = 2
    case tls = 3
    case unknown = 4
}

// MARK: - Base Socket Wrapper

/// Base class providing common socket operations with zero-copy data handling
@objc public class SocketWrapper: NSObject {
    internal var connection: NWConnection?

    internal override init() {
        super.init()
    }

    internal init(connection: NWConnection) {
        self.connection = connection
        super.init()
    }

    @objc public var isOpen: Bool {
        connection?.state == .ready
    }

    @objc public var localPort: Int {
        guard let endpoint = connection?.currentPath?.localEndpoint else { return -1 }
        if case .hostPort(_, let port) = endpoint {
            return Int(port.rawValue)
        }
        return -1
    }

    @objc public var remotePort: Int {
        guard let endpoint = connection?.currentPath?.remoteEndpoint else { return -1 }
        if case .hostPort(_, let port) = endpoint {
            return Int(port.rawValue)
        }
        return -1
    }

    /// Zero-copy read - returns NSData directly without copying
    /// The returned NSData shares memory with the network buffer
    @objc public func read(completion: @escaping (NSData?, SocketErrorType, String?, Bool) -> Void) {
        guard let connection = connection else {
            completion(nil, .unknown, "Connection not initialized", true)
            return
        }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
            // Check for POSIX ENODATA (error 96) which means peer closed connection
            // Treat this as isComplete=true rather than an error
            var effectiveIsComplete = isComplete
            var effectiveError = error
            if let nwError = error, case .posix(let posixCode) = nwError {
                // ENODATA = 96 on Darwin, means no data available (peer closed)
                if posixCode.rawValue == 96 {
                    effectiveIsComplete = true
                    effectiveError = nil
                }
            }
            let errorType = Self.mapError(effectiveError)
            // Data bridges to NSData without copying when passed to Obj-C
            completion(data as NSData?, errorType, Self.getDetailedErrorDescription(effectiveError), effectiveIsComplete)
        }
    }

    /// Zero-copy write - takes NSData directly
    /// The NSData is passed to Network.framework without copying
    @objc public func write(data: NSData, completion: @escaping (Int, SocketErrorType, String?) -> Void) {
        guard let connection = connection else {
            completion(0, .unknown, "Connection not initialized")
            return
        }
        let byteCount = data.length
        // Data(referencing:) creates a view without copying
        connection.send(content: Data(referencing: data), completion: .contentProcessed { error in
            let errorType = Self.mapError(error)
            completion(error == nil ? byteCount : 0, errorType, Self.getDetailedErrorDescription(error))
        })
    }

    @objc public func close() {
        connection?.cancel()
    }

    @objc public func forceClose() {
        connection?.forceCancel()
    }

    internal static func mapError(_ error: NWError?) -> SocketErrorType {
        guard let error = error else { return .none }
        switch error {
        case .posix(_): return .posix
        case .dns(_): return .dns
        case .tls(_): return .tls
        default: return .unknown
        }
    }

    /// Get a detailed error description, especially for TLS errors
    /// Error codes from Apple's Secure Transport Result Codes documentation:
    /// https://developer.apple.com/documentation/security/secure_transport/1503828-secure_transport_result_codes
    internal static func getDetailedErrorDescription(_ error: NWError?) -> String? {
        guard let error = error else { return nil }
        switch error {
        case .posix(let posixCode):
            return "POSIX error \(posixCode.rawValue): \(String(cString: strerror(posixCode.rawValue)))"
        case .dns(let dnsCode):
            return "DNS error \(dnsCode): \(error.debugDescription)"
        case .tls(let tlsStatus):
            // Map OSStatus TLS error codes per Apple's Secure Transport Result Codes
            let message: String
            switch tlsStatus {
            case -9800: message = "errSSLProtocol: SSL protocol error"
            case -9801: message = "errSSLNegotiation: Cipher suite negotiation failure"
            case -9802: message = "errSSLFatalAlert: Fatal alert"
            case -9803: message = "errSSLWouldBlock: I/O would block (not fatal)"
            case -9804: message = "errSSLSessionNotFound: Attempt to restore unknown session"
            case -9805: message = "errSSLClosedGraceful: Connection closed gracefully"
            case -9806: message = "errSSLClosedAbort: Connection closed via error"
            case -9807: message = "errSSLXCertChainInvalid: Invalid certificate chain"
            case -9808: message = "errSSLBadCert: Bad certificate format"
            case -9809: message = "errSSLCrypto: Underlying cryptographic error"
            case -9810: message = "errSSLInternal: Internal error"
            case -9811: message = "errSSLModuleAttach: Module attach failure"
            case -9812: message = "errSSLUnknownRootCert: Valid cert chain, untrusted root"
            case -9813: message = "errSSLNoRootCert: No root certificate for cert chain"
            case -9814: message = "errSSLCertExpired: Chain had an expired cert"
            case -9815: message = "errSSLCertNotYetValid: Chain had a cert not yet valid"
            case -9816: message = "errSSLClosedNoNotify: Server closed session with no notification"
            case -9817: message = "errSSLBufferOverflow: Insufficient buffer provided"
            case -9818: message = "errSSLBadCipherSuite: Bad SSLCipherSuite"
            case -9819: message = "errSSLPeerUnexpectedMsg: Unexpected message received"
            case -9820: message = "errSSLPeerBadRecordMac: Bad MAC"
            case -9821: message = "errSSLPeerDecryptionFail: Decryption failed"
            case -9822: message = "errSSLPeerRecordOverflow: Record overflow"
            case -9823: message = "errSSLPeerDecompressFail: Decompression failure"
            case -9824: message = "errSSLPeerHandshakeFail: Handshake failure"
            case -9825: message = "errSSLPeerBadCert: Misc. bad certificate"
            case -9826: message = "errSSLPeerUnsupportedCert: Bad unsupported cert format"
            case -9827: message = "errSSLPeerCertRevoked: Certificate revoked"
            case -9828: message = "errSSLPeerCertExpired: Certificate expired"
            case -9829: message = "errSSLPeerCertUnknown: Unknown certificate"
            case -9830: message = "errSSLIllegalParam: Illegal parameter"
            case -9831: message = "errSSLPeerUnknownCA: Unknown Cert Authority"
            case -9832: message = "errSSLPeerAccessDenied: Access denied"
            case -9833: message = "errSSLPeerDecodeError: Decoding error"
            case -9834: message = "errSSLPeerDecryptError: Decryption error"
            case -9835: message = "errSSLPeerExportRestriction: Export restriction"
            case -9836: message = "errSSLPeerProtocolVersion: Bad protocol version"
            case -9837: message = "errSSLPeerInsufficientSecurity: Insufficient security"
            case -9838: message = "errSSLPeerInternalError: Internal error"
            case -9839: message = "errSSLPeerUserCancelled: User canceled"
            case -9840: message = "errSSLPeerNoRenegotiation: No renegotiation allowed"
            case -9841: message = "errSSLPeerAuthCompleted: Peer cert is valid, or was ignored if verification disabled"
            case -9842: message = "errSSLClientCertRequested: Server has requested a client cert"
            case -9843: message = "errSSLHostNameMismatch: Peer host name mismatch"
            case -9844: message = "errSSLConnectionRefused: Peer dropped connection before responding"
            case -9845: message = "errSSLDecryptionFail: Decryption failure"
            case -9846: message = "errSSLBadRecordMac: Bad MAC"
            case -9847: message = "errSSLRecordOverflow: Record overflow"
            case -9848: message = "errSSLBadConfiguration: Configuration error"
            case -9849: message = "errSSLUnexpectedRecord: Unexpected (skipped) record in DTLS"
            case -67843: message = "errSecNotTrusted: Certificate not trusted"
            default: message = "OSStatus \(tlsStatus)"
            }
            return "TLS error \(tlsStatus): \(message)"
        default:
            return error.debugDescription
        }
    }
}

// MARK: - Client Socket

/// Client socket wrapper for outbound connections
@objc public class ClientSocketWrapper: SocketWrapper {

    @objc public init(host: String, port: UInt16, timeoutSeconds: Int, useTLS: Bool, verifyCertificates: Bool = true) {
        super.init()

        let nwHost = NWEndpoint.Host(host)
        let nwPort = NWEndpoint.Port(rawValue: port)!

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.connectionTimeout = timeoutSeconds

        let params: NWParameters
        if useTLS {
            let tlsOptions = NWProtocolTLS.Options()
            // Enable peer authentication by default for security
            // Only disable when explicitly requested (e.g., for self-signed certificates)
            sec_protocol_options_set_peer_authentication_required(
                tlsOptions.securityProtocolOptions, verifyCertificates
            )
            params = NWParameters(tls: tlsOptions, tcp: tcpOptions)
        } else {
            params = NWParameters(tls: nil, tcp: tcpOptions)
        }

        self.connection = NWConnection(host: nwHost, port: nwPort, using: params)
    }

    @objc public func setStateHandler(
        handler: @escaping (ClientSocketWrapper, String, SocketErrorType, String?) -> Void
    ) {
        connection?.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            let stateString = "\(state)"

            switch state {
            case .ready:
                handler(self, stateString, .none, nil)
            case .failed(let error), .waiting(let error):
                let errorType = Self.mapError(error)
                handler(self, stateString, errorType, Self.getDetailedErrorDescription(error))
            case .setup, .preparing:
                handler(self, stateString, .none, nil)
            case .cancelled:
                handler(self, stateString, .none, nil)
            @unknown default:
                handler(self, stateString, .unknown, "Unknown state")
            }
        }
    }

    @objc public func start() {
        connection?.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    @objc public var currentState: String {
        guard let connection = connection else { return "not initialized" }
        return "\(connection.state)"
    }
}

// MARK: - Server-to-Client Socket

/// Wrapper for accepted server-side connections
@objc public class ServerToClientSocketWrapper: SocketWrapper {

    private var readyHandler: ((ServerToClientSocketWrapper) -> Void)?
    private var errorHandler: ((ServerToClientSocketWrapper, SocketErrorType, String?) -> Void)?

    internal func configure(
        connection: NWConnection,
        readyHandler: @escaping (ServerToClientSocketWrapper) -> Void,
        errorHandler: @escaping (ServerToClientSocketWrapper, SocketErrorType, String?) -> Void
    ) {
        self.connection = connection
        self.readyHandler = readyHandler
        self.errorHandler = errorHandler

        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }

            switch state {
            case .ready:
                self.readyHandler?(self)
            case .failed(let error), .waiting(let error):
                let errorType = Self.mapError(error)
                self.errorHandler?(self, errorType, Self.getDetailedErrorDescription(error))
                self.close()
            case .setup, .preparing, .cancelled:
                break
            @unknown default:
                break
            }
        }

        connection.start(queue: DispatchQueue.global(qos: .userInitiated))
    }
}

// MARK: - Server Listener

/// Server socket listener for accepting inbound connections
@objc public class ServerListenerWrapper: NSObject {
    private var listener: NWListener?
    private var acceptHandler: ((ServerToClientSocketWrapper) -> Void)?
    private var closeHandlers: [() -> Void] = []
    /// Holds strong references to pending connections until they become ready
    private var pendingConnections: Set<ServerToClientSocketWrapper> = []
    private let pendingLock = NSLock()

    @objc public override init() {
        super.init()
    }

    @objc public func configure(port: Int, backlog: Int) -> Bool {
        let nwPort: NWEndpoint.Port = port < 0 ? .any : NWEndpoint.Port(rawValue: UInt16(port))!

        do {
            listener = try NWListener(using: .tcp, on: nwPort)
        } catch {
            return false
        }

        if backlog < 1 {
            listener?.newConnectionLimit = NWListener.InfiniteConnectionLimit
        } else {
            listener?.newConnectionLimit = backlog
        }

        listener?.newConnectionHandler = { [weak self] connection in
            guard let self = self, let handler = self.acceptHandler else { return }
            let wrapper = ServerToClientSocketWrapper()

            // Keep strong reference until connection is ready or fails
            self.pendingLock.lock()
            self.pendingConnections.insert(wrapper)
            self.pendingLock.unlock()

            wrapper.configure(
                connection: connection,
                readyHandler: { [weak self] readyWrapper in
                    // Remove from pending and notify handler
                    self?.pendingLock.lock()
                    self?.pendingConnections.remove(readyWrapper)
                    self?.pendingLock.unlock()
                    handler(readyWrapper)
                },
                errorHandler: { [weak self] failedWrapper, _, _ in
                    // Remove from pending, connection failed before becoming ready
                    self?.pendingLock.lock()
                    self?.pendingConnections.remove(failedWrapper)
                    self?.pendingLock.unlock()
                }
            )
        }

        return true
    }

    @objc public func setAcceptHandler(handler: @escaping (ServerToClientSocketWrapper) -> Void) {
        acceptHandler = handler
    }

    @objc public func addCloseHandler(handler: @escaping () -> Void) {
        closeHandlers.append(handler)
    }

    @objc public func start(
        completion: @escaping (Bool, SocketErrorType, String?) -> Void
    ) {
        guard let listener = listener else {
            completion(false, .unknown, "Listener not configured")
            return
        }

        listener.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }

            switch state {
            case .ready:
                completion(true, .none, nil)
            case .failed(let error), .waiting(let error):
                let errorType = SocketWrapper.mapError(error)
                completion(false, errorType, SocketWrapper.getDetailedErrorDescription(error))
            case .cancelled:
                self.acceptHandler = nil
                self.pendingLock.lock()
                self.pendingConnections.removeAll()
                self.pendingLock.unlock()
                for handler in self.closeHandlers {
                    handler()
                }
                self.closeHandlers.removeAll()
            case .setup:
                break
            @unknown default:
                break
            }
        }

        listener.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    @objc public var isListening: Bool {
        listener?.state == .ready
    }

    @objc public var boundPort: Int {
        guard isListening, let port = listener?.port else { return -1 }
        return Int(port.rawValue)
    }

    @objc public func stop(completion: @escaping () -> Void) {
        guard let listener = listener else {
            completion()
            return
        }
        if listener.state == .cancelled {
            completion()
        } else {
            closeHandlers.append(completion)
            listener.cancel()
        }
    }
}

// MARK: - Port Helper

/// Utility to check if a port is available
@objc public class PortHelper: NSObject {

    @objc public static func isPortAvailable(_ port: Int) -> Bool {
        let socketFD = socket(AF_INET, SOCK_STREAM, 0)
        guard socketFD != -1 else { return false }
        defer { Darwin.close(socketFD) }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = CFSwapInt16HostToBig(UInt16(port))
        addr.sin_addr = in_addr(s_addr: inet_addr("0.0.0.0"))

        let bindResult = withUnsafePointer(to: &addr) { addrPtr in
            addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPtr in
                Darwin.bind(socketFD, sockaddrPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard bindResult != -1 else { return false }
        return listen(socketFD, SOMAXCONN) != -1
    }
}
