---
sidebar_position: 4
title: TLS Support Matrix
---

# TLS Support Matrix

This document details the TLS/SSL support levels across different platforms in the Socket library.

## Platform Compatibility

| Feature | JVM/Android | Linux | macOS/iOS | Node.js | Browser |
|---------|-------------|-------|-----------|---------|---------|
| TLS Connections | ✅ | ✅ | ✅ | ✅ | ❌ WebSocket only |
| TLS 1.2 | ✅ | ✅ | ✅ | ✅ | N/A |
| TLS 1.3 | ✅ | ✅ | ✅ | ✅ | N/A |
| SNI (Server Name Indication) | ✅ | ✅ | ✅ | ✅ | N/A |
| Certificate Validation | ✅ Default | ✅ Configurable | ✅ Configurable | ✅ Configurable | N/A |
| Hostname Verification | ✅ | ✅ | ✅ | ✅ | N/A |

## TLS Configuration

TLS is configured through `SocketOptions` with a `TlsConfig`:

```kotlin
val socket = ClientSocket.connect(
    port = 443,
    hostname = "example.com",
    socketOptions = SocketOptions(
        tls = TlsConfig(
            verifyCertificates = true,  // Verify against trusted CAs
            verifyHostname = true,       // Verify hostname matches certificate
        )
    ),
)
```

### Available Options

| Option | Default | Description |
|--------|---------|-------------|
| `verifyCertificates` | `true` | Verify server certificate against trusted CA store |
| `verifyHostname` | `true` | Verify certificate hostname matches connection hostname |
| `allowExpiredCertificates` | `false` | Allow expired certificates (testing only) |
| `allowSelfSigned` | `false` | Allow self-signed certificates (testing only) |

### Presets

For convenience, presets are available on both `SocketOptions` and `TlsConfig`:

```kotlin
// Production: TLS with all validation enabled
val strictOptions = SocketOptions.tlsDefault()

// Development/Testing: TLS with validation disabled
val devOptions = SocketOptions.tlsInsecure()

// Or use TlsConfig directly for custom SocketOptions
val custom = SocketOptions(tcpNoDelay = true, tls = TlsConfig.DEFAULT)
```

**Warning:** Only use `SocketOptions.tlsInsecure()` / `TlsConfig.INSECURE` for development and testing. Never use in production.

## Platform Implementation Details

### JVM/Android

- **Implementation**: `SSLEngine` wrapping NIO sockets
- **Trust Store**: Uses system trust store (`cacerts`)
- **Certificate Validation**: Enforced by default via `SSLContext`
- **Hostname Verification**: Automatic via `SSLEngine.setSSLParameters()`

### Linux Native

- **Implementation**: OpenSSL 3.0 (statically linked)
- **Trust Store**: System CA certificates (`/etc/ssl/certs/`, `/etc/pki/tls/certs/`)
- **Certificate Validation**: Configurable via `SSL_CTX_set_verify()`
- **SNI**: Configured via `SSL_set_tlsext_host_name()`

Supported CA certificate locations:
- `/etc/ssl/certs/ca-certificates.crt` (Debian/Ubuntu)
- `/etc/pki/tls/certs/ca-bundle.crt` (RHEL/Fedora/CentOS)
- `/etc/ssl/ca-bundle.pem` (OpenSUSE)
- `/etc/ssl/cert.pem` (Alpine/Arch)

### Apple (macOS/iOS)

- **Implementation**: Network.framework (`NWConnection` with `NWProtocolTLS`)
- **Trust Store**: System Keychain
- **Certificate Validation**: Configurable via `sec_protocol_options_set_peer_authentication_required()`
- **SNI**: Automatic based on hostname

### Node.js

- **Implementation**: Node.js `tls` module
- **Trust Store**: System CA store or bundled Mozilla CA bundle
- **Certificate Validation**: Configurable via `rejectUnauthorized` option
- **SNI**: Configurable via `servername` option

### Browser

Browser environments only support WebSocket connections. Direct TCP/TLS sockets are not available due to browser security restrictions.

## Known Limitations

### Certificate Validation Caveats

1. **Self-signed certificates** require `allowSelfSigned = true`
2. **Expired certificates** require `allowExpiredCertificates = true`
3. **Hostname mismatch** requires `verifyHostname = false`

### Platform-Specific Behaviors

- **Node.js**: Some servers with strict cipher requirements may fail with "handshake failure" errors
- **Linux**: OpenSSL error messages may differ from other platforms
- **JVM**: Some large responses may require multiple reads due to buffer sizing

## Testing TLS Connections

The library includes comprehensive TLS tests against public endpoints:

```kotlin
// Test against well-known HTTPS sites
ClientSocket.connect(443, "www.google.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    socket.writeString("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
    val response = socket.readString()
    assertTrue(response.startsWith("HTTP/"))
}

// Test certificate validation with badssl.com (should throw with default options)
ClientSocket.connect(443, "expired.badssl.com", socketOptions = SocketOptions.tlsDefault()) { socket ->
    // Should throw SSLHandshakeFailedException with default options
}
```

### Tested Endpoints

| Host | Status | Notes |
|------|--------|-------|
| google.com | ✅ Reliable | All platforms |
| cloudflare.com | ✅ Reliable | All platforms |
| httpbin.org | ✅ Reliable | All platforms |
| example.com | ⚠️ Variable | Some Node.js issues |
| nginx.org | ⚠️ Variable | Some Node.js issues |

## Security Recommendations

1. **Always use `SocketOptions.tlsDefault()`** in production (certificate validation enabled)
2. **Never disable certificate validation** in production code
3. **Pin certificates** for high-security applications (not yet supported by this library)
4. **Keep dependencies updated** for security patches
5. **Monitor TLS version support** - TLS 1.0/1.1 are deprecated
