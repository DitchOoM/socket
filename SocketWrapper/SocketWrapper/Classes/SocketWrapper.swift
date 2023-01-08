//
//  ClientSocketWrapper.swift
//  SocketWrapper
//
//  Created by Rahul Behera on 12/30/22.
//

import Foundation
import Network

@objc
public class ClientSocketWrapper: SocketWrapper {
  let host: Network.NWEndpoint.Host
  let port: Network.NWEndpoint.Port
    
  @objc public init(
    host: String, port: UInt16, timeoutSeconds: Int, tls: Bool,
    completion: @escaping (ClientSocketWrapper, String?, Bool, Bool, Bool) -> Void
  ) {
    self.host = NWEndpoint.Host(host)
    self.port = NWEndpoint.Port(rawValue: port)!
    let nwConnection :NWConnection
    if tls {
        let options = NWProtocolTCP.Options()
        options.connectionTimeout = timeoutSeconds
        let tlsOptions = NWProtocolTLS.Options()
        
        sec_protocol_options_set_peer_authentication_required(tlsOptions.securityProtocolOptions, false)
        
        let params = NWParameters(tls: tlsOptions, tcp: options)
        nwConnection = NWConnection(host: self.host, port: self.port, using: params)
    } else {
        let options = NWProtocolTCP.Options()
        options.connectionTimeout = timeoutSeconds
        let params = NWParameters(tls: nil, tcp: options)
        nwConnection = NWConnection(host: self.host, port: self.port, using: params)
    }
    super.init(connection: nwConnection)
    connection.stateUpdateHandler = { state in
        NSLog("state \(state)")
      switch state {
      case .ready:
          completion(self, nil, false, false, false)
      case .failed(let error):
          self.notifyCompletion(socketWrapper: self, error: error, completion: completion)
      case .waiting(let error):
          self.notifyCompletion(socketWrapper: self, error: error, completion: completion)
      default:
          ()
      }
    }
    connection.start(queue: DispatchQueue.global())
  }
    
    @objc public func currentState() -> String {
        return "\(self.connection.state)"
    }
    
    public override func describe() -> String {
        return "ClientSW: \(super.describe())"
    }
    
    
    func notifyCompletion(socketWrapper: ClientSocketWrapper, error: NWError, completion: @escaping (ClientSocketWrapper, String?, Bool, Bool, Bool) -> Void) {
        switch error {
        case .posix(_):
            completion(self, error.debugDescription, true, false, false)
        case .dns(_):
            completion(self, error.debugDescription, false, true, false)
        case .tls(_):
            completion(self, error.debugDescription, false, false, true)
        @unknown default:
            fatalError("Unknown NWError case \(error)")
        }
        close {}
    }
}

@objc
public class ServerSocketWrapper: SocketWrapper {
    
    public init(connection: NWConnection,
                         completion: @escaping (ServerSocketWrapper, String?, Bool, Bool, Bool) -> Void) {
        super.init(connection: connection)
        connection.stateUpdateHandler = { state in
            NSLog("state \(state)")
          switch state {
          case .ready:
              completion(self, nil, false, false, false)
          case .failed(let error):
              self.notifyCompletion(socketWrapper: self, error: error, completion: completion)
          case .waiting(let error):
              self.notifyCompletion(socketWrapper: self, error: error, completion: completion)
          default:
              ()
          }
        }
        connection.start(queue: DispatchQueue.global())
        
    }
    func notifyCompletion(socketWrapper: ServerSocketWrapper, error: NWError, completion: @escaping (ServerSocketWrapper, String?, Bool, Bool, Bool) -> Void) {
        switch error {
        case .posix(_):
            completion(self, error.debugDescription, true, false, false)
        case .dns(_):
            completion(self, error.debugDescription, false, true, false)
        case .tls(_):
            completion(self, error.debugDescription, false, false, true)
        @unknown default:
            fatalError("Unknown NWError case \(error)")
        }
        close {}
    }
}

extension Data {
  var bytes: [UInt8] {
    return [UInt8](self)
  }
}

extension Array where Element == UInt8 {
  var data: Data {
    return Data(self)
  }
}


@objc
public class ServerSocketListenerWrapper: NSObject {
    
    var listener: NWListener?
    
    @objc public func start(port: Int, host: String?, backlog:Int,
                    acceptedClient:@escaping (ServerSocketWrapper) -> Void,
                    completionHandler:@escaping (ServerSocketListenerWrapper, String?, Bool, Bool, Bool) -> Void) {
        let nwEndpointPort :NWEndpoint.Port
        if (port < 0) {
            nwEndpointPort = .any
        } else {
            nwEndpointPort = NWEndpoint.Port(rawValue: UInt16(port))!
        }
        let listener = try! NWListener(using: .tcp, on: nwEndpointPort)
        listener.stateUpdateHandler = { state in
            NSLog("Server update state \(state)")
            switch state {
            case .setup:
                ()
            case .waiting(let error):
                self.notifyCompletion(error: error, completionHandler: completionHandler)
            case .ready:
                completionHandler(self, nil, false, false, false)
            case .failed(let error):
                self.notifyCompletion(error: error, completionHandler: completionHandler)
            case .cancelled:
                ()
            @unknown default:
                ()
            }
        }
        if (backlog < 1) {
            listener.newConnectionLimit = NWListener.InfiniteConnectionLimit
        } else {
            listener.newConnectionLimit = backlog
        }
        listener.newConnectionHandler = { connection in
            NSLog("new connection \(connection)")
            let _ = ServerSocketWrapper(connection: connection) {
                (serverSocketWrapper, errorString, isPosixError, isDnsError, isTlsError) in
                if (errorString == nil && !isPosixError && !isDnsError && !isTlsError) {
                    acceptedClient(serverSocketWrapper)
                }
            }
        }
        self.listener = listener
        listener.start(queue: .global())
    }
    
    @objc public func isOpen() -> Bool {
        return listener?.state == .ready
    }
    
    @objc public func port() -> Int {
        guard let listenerPort = listener?.port else {
            return -1
        }
        return Int(listenerPort.rawValue)
    }
    
    @objc public func stopListeningForInboundConnections() {
        listener?.cancel()
    }
    
    func notifyCompletion(error: NWError, completionHandler: @escaping (ServerSocketListenerWrapper, String?, Bool, Bool, Bool) -> Void) {
        switch error {
        case .posix(_):
            completionHandler(self, error.debugDescription, true, false, false)
        case .dns(_):
            completionHandler(self, error.debugDescription, false, true, false)
        case .tls(_):
            completionHandler(self, error.debugDescription, false, false, true)
        @unknown default:
            fatalError("Unknown NWError case \(error)")
        }
    }
    
}


@objc
public class SocketWrapper: NSObject {
    let connection: NWConnection
    
    public init(connection: NWConnection) {
        self.connection = connection
    }
    
    @objc public func isOpen() -> Bool {
      return connection.state == NWConnection.State.ready
    }

    @objc public func localPort() -> Int {
      switch connection.currentPath?.localEndpoint as? NWEndpoint {
      case .hostPort(_, let port):
        return Int(port.rawValue)
      default:
        return -1
      }
    }
    @objc public func remotePort() -> Int {
      switch connection.currentPath?.remoteEndpoint as? NWEndpoint {
      case .hostPort(_, let port):
        return Int(port.rawValue)
      default:
        return -1
      }
    }
      @objc public func readData(completion: @escaping (Data, String?, Bool) -> Void) {
          NSLog("\(String(describing: self.describe())) read data")
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) {
          (data, _, isComplete, error) in
            NSLog("\(String(describing: self.describe())) recv \(data?.count ?? -1) \(error?.debugDescription) isComplete:\(isComplete)")
          guard let content = data else {
              completion(Data(count: 0), error?.debugDescription, isComplete)
            return
          }
          completion(content, error?.debugDescription, isComplete)
        }
          NSLog("\(describe()) read data1")
      }
      
      
      @objc public func writeData(buffer: Data, completion: @escaping (Int, String?) -> Void) {
        let byteCount = buffer.count
          NSLog("\(String(describing: self.describe())) Writing")
        connection.send(
          content: buffer,
          completion: .contentProcessed { error in
              NSLog("\(String(describing: self.describe())) Wrote \(error?.debugDescription)")
            if error != nil {
              completion(0, error?.debugDescription)
            } else {
              completion(byteCount, error?.debugDescription)
            }
          })
          
            NSLog("\(String(describing: self.describe())) Writing1")
      }

    @objc public func close(completionHandler: @escaping () -> Void) {
      connection.stateUpdateHandler = { update in
        switch update {
        case .cancelled:
          self.connection.forceCancel()
          completionHandler()
        case .failed(_):
          self.connection.forceCancel()
          completionHandler()
            
        default:
          break
        }
      }
      connection.cancel()
    }
    
    public func describe() -> String {
        return "\(String(describing: self))\(String(describing: localPort)):\(String(describing: remotePort))"
    }
}
