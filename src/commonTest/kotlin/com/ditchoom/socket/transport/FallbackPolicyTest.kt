package com.ditchoom.socket.transport

import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SSLProtocolException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.SocketUnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole of the fallback correctness is the error taxonomy → verdict mapping (RFC §4), so it is
 * exhaustively unit-tested here, purely (no coroutines, no network).
 */
class FallbackPolicyTest {
    private val policy = DefaultFallbackPolicy

    @Test
    fun badCertificateIsFatal() {
        val v =
            policy.classify(
                SSLHandshakeFailedException("bad cert", reason = ConnectionFailureReason.TlsBadCertificate),
            )
        assertFalse(v.fallback, "bad cert must not fall back")
        assertFalse(v.cacheUnsupported, "fatal errors are never cached")
        assertEquals(CacheScope.None, v.scope)
    }

    @Test
    fun refusedIsPerHostCapability() {
        val v = policy.classify(SocketConnectionException.Refused("h", 1))
        assertTrue(v.fallback)
        assertTrue(v.cacheUnsupported)
        assertEquals(CacheScope.PerHost, v.scope)
    }

    @Test
    fun connectionResetIsPerHostCapability() {
        val v = policy.classify(SocketClosedException.ConnectionReset("rst"))
        assertTrue(v.fallback)
        assertTrue(v.cacheUnsupported)
        assertEquals(CacheScope.PerHost, v.scope)
    }

    @Test
    fun protocolOrAlpnMismatchIsPerHostCapability() {
        val v = policy.classify(SSLProtocolException("alpn mismatch"))
        assertTrue(v.fallback)
        assertTrue(v.cacheUnsupported)
        assertEquals(CacheScope.PerHost, v.scope)
    }

    @Test
    fun timeoutIsTransientAndNeverCached() {
        val v = policy.classify(SocketTimeoutException("connect timed out"))
        assertEquals(FallbackVerdict.Transient, v)
    }

    @Test
    fun unreachableIsTransient() {
        assertEquals(FallbackVerdict.Transient, policy.classify(SocketConnectionException.NetworkUnreachable("net")))
        assertEquals(FallbackVerdict.Transient, policy.classify(SocketConnectionException.HostUnreachable("host")))
    }

    @Test
    fun dnsFailureIsTransient() {
        assertEquals(FallbackVerdict.Transient, policy.classify(SocketUnknownHostException("no.such.host")))
    }

    @Test
    fun genericHandshakeFallsForwardWithoutPoisoning() {
        // Default reason is TlsHandshake — could be ALPN (capability) or cert-ish (fatal); indistinguishable.
        // Fall forward so an ALPN mismatch still reaches TCP/WS, but do not poison the cache.
        val v = policy.classify(SSLHandshakeFailedException("handshake failed"))
        assertTrue(v.fallback)
        assertFalse(v.cacheUnsupported)
    }

    @Test
    fun uncategorizedErrorsFallForwardWithoutPoisoning() {
        assertEquals(FallbackVerdict.Transient, policy.classify(SocketIOException("weird io")))
        assertEquals(FallbackVerdict.Transient, policy.classify(SocketClosedException.General("closed")))
        assertEquals(FallbackVerdict.Transient, policy.classify(RuntimeException("non-socket throwable")))
    }
}
