@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecPolicyCreateSSL
import platform.Security.SecTrustCreateWithCertificates
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRefVar
import platform.Security.errSecSuccess

/**
 * [AppleTrustSource.SystemTrustStore] (#186): the server's chain evaluated against the device's own trust
 * store by Security.framework, with an SSL server policy for the name the connection asked for — the same
 * evaluation the device's own TLS runs, so MDM- and user-installed roots, OS revocation and certificate
 * transparency policy all apply. BoringSSL's in-handshake verification is turned off for these connections
 * (iOS ships no CA file for it to load), and this runs after the handshake, before the connection is handed
 * to the caller.
 *
 * `SecTrustEvaluateWithError` blocks (it may fetch revocation or intermediates over the network), so it
 * runs on [Dispatchers.Default], never on whatever dispatcher the connect was called from.
 */
internal val appleSystemChainTrust =
    SystemChainTrust { serverName, chain -> withContext(Dispatchers.Default) { evaluateWithSecTrust(serverName, chain) } }

/** `SecTrustEvaluateWithError` blocks; Apple asks that it never run on the main thread, hence Default above. */
private fun evaluateWithSecTrust(
    serverName: String,
    chain: List<ReadBuffer>,
): String? {
    val owned = mutableListOf<CFTypeRef?>()
    try {
        return memScoped {
            val certificates = CFArrayCreateMutable(null, chain.size.convert(), kCFTypeArrayCallBacks.ptr)
            owned += certificates
            for ((index, der) in chain.withIndex()) {
                val data = cfData(der) ?: return "certificate $index could not be copied"
                owned += data
                val certificate =
                    SecCertificateCreateWithData(null, data)
                        ?: return "certificate $index is not a DER X.509 certificate"
                owned += certificate
                CFArrayAppendValue(certificates, certificate)
            }
            val host = CFStringCreateWithCString(null, serverName, kCFStringEncodingUTF8)
            owned += host
            val policy = SecPolicyCreateSSL(true, host)
            owned += policy
            val trust = alloc<SecTrustRefVar>()
            val created = SecTrustCreateWithCertificates(certificates, policy, trust.ptr)
            if (created != errSecSuccess || trust.value == null) return "SecTrustCreateWithCertificates failed ($created)"
            owned += trust.value
            val error = alloc<CFErrorRefVar>()
            if (SecTrustEvaluateWithError(trust.value, error.ptr)) {
                null
            } else {
                val err = error.value
                if (err == null) {
                    "SecTrustEvaluateWithError refused the chain"
                } else {
                    owned += err
                    val description = CFBridgingRelease(CFErrorCopyDescription(err)) as? String
                    "${description ?: "untrusted"} (CFError ${CFErrorGetCode(err)})"
                }
            }
        }
    } finally {
        owned.forEach { if (it != null) CFRelease(it) }
    }
}

/** A CFData holding [der]'s remaining bytes, or null if CoreFoundation could not allocate one. */
private fun cfData(der: ReadBuffer) =
    memScoped {
        val length = der.remaining()
        val bytes = allocArray<UByteVar>(length)
        val start = der.position()
        for (i in 0 until length) bytes[i] = der[start + i].toUByte()
        CFDataCreate(null, bytes, length.convert())
    }
