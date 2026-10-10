@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SystemTrustFailure
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
 * [AppleTrustSource.SystemTrustStore]: the chain evaluated by Security.framework against the device trust
 * store with an SSL policy for the server name. `SecTrustEvaluateWithError` blocks (it may fetch revocation
 * or intermediates), so it runs on [Dispatchers.Default].
 */
internal val appleSystemChainTrust =
    PlatformChainTrust.AfterHandshake { serverName, chain ->
        withContext(Dispatchers.Default) { evaluateWithSecTrust(serverName, chain) }
    }

private fun evaluateWithSecTrust(
    serverName: String,
    chain: List<ReadBuffer>,
): SystemTrustVerdict {
    val owned = mutableListOf<CFTypeRef?>()
    try {
        return memScoped {
            val certificates = CFArrayCreateMutable(null, chain.size.convert(), kCFTypeArrayCallBacks.ptr)
            owned += certificates
            for ((index, der) in chain.withIndex()) {
                val data = cfData(der) ?: return untrusted(SystemTrustFailure.CopyFailed(index))
                owned += data
                val certificate =
                    SecCertificateCreateWithData(null, data)
                        ?: return untrusted(SystemTrustFailure.UnreadableCertificate(index))
                owned += certificate
                CFArrayAppendValue(certificates, certificate)
            }
            val host = CFStringCreateWithCString(null, serverName, kCFStringEncodingUTF8)
            owned += host
            val policy = SecPolicyCreateSSL(true, host)
            owned += policy
            val trust = alloc<SecTrustRefVar>()
            val created = SecTrustCreateWithCertificates(certificates, policy, trust.ptr)
            if (created != errSecSuccess || trust.value == null) return untrusted(SystemTrustFailure.TrustCreationFailed(created))
            owned += trust.value
            val error = alloc<CFErrorRefVar>()
            if (SecTrustEvaluateWithError(trust.value, error.ptr)) {
                SystemTrustVerdict.Trusted
            } else {
                when (val err = error.value) {
                    null -> untrusted(SystemTrustFailure.RefusedWithoutError)
                    else -> {
                        owned += err
                        val description = CFBridgingRelease(CFErrorCopyDescription(err)).toString()
                        untrusted(SystemTrustFailure.Refused(CFErrorGetCode(err).convert(), description))
                    }
                }
            }
        }
    } finally {
        owned.forEach { if (it != null) CFRelease(it) }
    }
}

private fun untrusted(failure: SystemTrustFailure) = SystemTrustVerdict.Untrusted(failure)

/** A CFData holding [der]'s remaining bytes; null when CoreFoundation cannot allocate (`CFDataCreate`). */
private fun cfData(der: ReadBuffer) =
    memScoped {
        val length = der.remaining()
        val bytes = allocArray<UByteVar>(length)
        val start = der.position()
        for (i in 0 until length) bytes[i] = der[start + i].toUByte()
        CFDataCreate(null, bytes, length.convert())
    }
