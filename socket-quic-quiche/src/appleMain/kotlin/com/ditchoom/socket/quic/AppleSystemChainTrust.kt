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
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecPolicyCreateSSL
import platform.Security.SecTrustCreateWithCertificates
import platform.Security.SecTrustEvaluateAsyncWithError
import platform.Security.SecTrustRef
import platform.Security.SecTrustRefVar
import platform.Security.errSecSuccess
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

/**
 * [AppleTrustSource.SystemTrustStore]: the chain evaluated by Security.framework against the device trust
 * store with an SSL policy for the server name.
 *
 * Evaluated with `SecTrustEvaluateAsyncWithError`, called from the dispatch queue it answers on (its
 * documented contract). It may fetch revocation data or intermediates and answers when it is done. The coroutine suspends cancellably on that answer and
 * holds no thread meanwhile; the synchronous `SecTrustEvaluateWithError` blocked one, and could not be
 * interrupted, so a slow evaluation held the connect past its deadline and past its cancellation.
 * (Available on every Apple target built here: macOS 10.15, iOS 13, tvOS 13, watchOS 6.)
 *
 * The chain's Core Foundation objects are released when BOTH the call into Security.framework has
 * returned and its callback has run, in whichever order those happen ([TwoPartyHold]), and whether or
 * not anything is still waiting: a cancelled evaluation is abandoned, not freed under Security.framework.
 * Releasing them from the callback alone is a use-after-free: the callback is delivered on a concurrent
 * queue and can run on another thread while `SecTrustEvaluateAsyncWithError` is still executing, which
 * then touches the trust object it was given (the crash was in `dispatch_group_leave` under
 * `SecTrustEvaluateIfNecessaryFastAsync`, inside that call).
 * Every certificate is copied into a CFData before the call, so nothing here refers to the caller's
 * buffers once it has started.
 */
internal val appleSystemChainTrust =
    PlatformChainTrust.AfterHandshake { serverName, chain -> evaluateWithSecTrust(serverName, chain) }

/** The Core Foundation objects an evaluation owns, released exactly once by whoever finishes with them. */
private class OwnedCf {
    private val objects = mutableListOf<CFTypeRef?>()

    fun <T : CFTypeRef?> keep(obj: T): T {
        objects += obj
        return obj
    }

    fun release() {
        objects.forEach { if (it != null) CFRelease(it) }
        objects.clear()
    }
}

/**
 * Something two parties each hold until they are done with it, released by whichever lets go last and
 * exactly once. Here the parties are the call into Security.framework and the callback it delivers; they
 * finish on different threads in either order, so the count is the only thing both can agree on.
 */
internal class TwoPartyHold(
    private val release: () -> Unit,
) {
    private val holders = AtomicInt(2)

    /** One party is done. The second call, from whichever thread, releases. */
    fun letGo() {
        if (holders.decrementAndGet() == 0) release()
    }
}

/** What building the trust object came to: one to evaluate, or the verdict that ends it before that. */
private sealed interface Prepared {
    class Ready(
        val trust: SecTrustRef,
        val owned: OwnedCf,
    ) : Prepared

    class Refused(
        val verdict: SystemTrustVerdict,
    ) : Prepared
}

private suspend fun evaluateWithSecTrust(
    serverName: String,
    chain: List<ReadBuffer>,
): SystemTrustVerdict =
    when (val prepared = prepare(serverName, chain)) {
        is Prepared.Refused -> prepared.verdict
        is Prepared.Ready ->
            suspendCancellableCoroutine { continuation ->
                // Security.framework's contract (SecTrust.h): "this function MUST be called from that
                // queue" — the one the callback is delivered on. So the call itself is dispatched there
                // rather than made from whichever thread the coroutine happens to be on. Any work the
                // evaluation does inline (the header allows the callback to run synchronously) then
                // also happens on a dispatch thread, not on a coroutine dispatcher's.
                val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)
                val hold = TwoPartyHold(prepared.owned::release)
                dispatch_async(queue) {
                    val started =
                        SecTrustEvaluateAsyncWithError(prepared.trust, queue) { _, trusted, error ->
                            // Runs on a dispatch thread, once, possibly after the caller has gone (resuming
                            // a cancelled continuation is ignored) and possibly before the call that
                            // started it has returned, so it only lets go of its share of the objects.
                            val verdict =
                                when {
                                    trusted -> SystemTrustVerdict.Trusted
                                    error == null -> untrusted(SystemTrustFailure.RefusedWithoutError)
                                    else -> {
                                        val description = CFBridgingRelease(CFErrorCopyDescription(error)).toString()
                                        untrusted(SystemTrustFailure.Refused(CFErrorGetCode(error).convert(), description))
                                    }
                                }
                            hold.letGo()
                            continuation.resume(verdict)
                        }
                    if (started != errSecSuccess) {
                        // The callback will not run, so its share is let go here.
                        hold.letGo()
                        continuation.resume(untrusted(SystemTrustFailure.TrustCreationFailed(started)))
                    }
                    // The call has returned: Security.framework is no longer executing with the trust.
                    hold.letGo()
                }
            }
    }

private fun prepare(
    serverName: String,
    chain: List<ReadBuffer>,
): Prepared {
    val owned = OwnedCf()
    return memScoped {
        val certificates = owned.keep(CFArrayCreateMutable(null, chain.size.convert(), kCFTypeArrayCallBacks.ptr))
        for ((index, der) in chain.withIndex()) {
            val data = cfData(der)
            if (data == null) {
                owned.release()
                return@memScoped Prepared.Refused(untrusted(SystemTrustFailure.CopyFailed(index)))
            }
            owned.keep(data)
            val certificate = SecCertificateCreateWithData(null, data)
            if (certificate == null) {
                owned.release()
                return@memScoped Prepared.Refused(untrusted(SystemTrustFailure.UnreadableCertificate(index)))
            }
            owned.keep(certificate)
            CFArrayAppendValue(certificates, certificate)
        }
        val host = owned.keep(CFStringCreateWithCString(null, serverName, kCFStringEncodingUTF8))
        val policy = owned.keep(SecPolicyCreateSSL(true, host))
        val trust = alloc<SecTrustRefVar>()
        val created = SecTrustCreateWithCertificates(certificates, policy, trust.ptr)
        val made = trust.value
        if (created != errSecSuccess || made == null) {
            owned.release()
            return@memScoped Prepared.Refused(untrusted(SystemTrustFailure.TrustCreationFailed(created)))
        }
        owned.keep(made)
        Prepared.Ready(made, owned)
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
