package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [mapErrnoToException] — the single errno → exception mapping function on Linux.
 *
 * Each test passes a synthetic errno and verifies the returned exception subtype.
 * No real I/O, no sockets, no network — pure mapping logic.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxExceptionMappingTests {
    @Test
    fun econnrefused_producesRefused() {
        val ex = mapErrnoToException(ECONNREFUSED, "connect")
        assertIs<SocketConnectionException.Refused>(ex)
    }

    @Test
    fun econnreset_producesConnectionReset() {
        val ex = mapErrnoToException(ECONNRESET, "recv")
        assertIs<SocketClosedException.ConnectionReset>(ex)
    }

    @Test
    fun econnaborted_producesConnectionReset() {
        val ex = mapErrnoToException(ECONNABORTED, "recv")
        assertIs<SocketClosedException.ConnectionReset>(ex)
    }

    @Test
    fun epipe_producesBrokenPipe() {
        val ex = mapErrnoToException(EPIPE, "send")
        assertIs<SocketClosedException.BrokenPipe>(ex)
    }

    @Test
    fun enotconn_producesBrokenPipe() {
        val ex = mapErrnoToException(ENOTCONN, "send")
        assertIs<SocketClosedException.BrokenPipe>(ex)
    }

    @Test
    fun eshutdown_producesBrokenPipe() {
        val ex = mapErrnoToException(ESHUTDOWN, "send")
        assertIs<SocketClosedException.BrokenPipe>(ex)
    }

    @Test
    fun enetunreach_producesNetworkUnreachable() {
        val ex = mapErrnoToException(ENETUNREACH, "connect")
        assertIs<SocketConnectionException.NetworkUnreachable>(ex)
    }

    @Test
    fun ehostunreach_producesHostUnreachable() {
        val ex = mapErrnoToException(EHOSTUNREACH, "connect")
        assertIs<SocketConnectionException.HostUnreachable>(ex)
    }

    @Test
    fun etimedout_producesSocketTimeoutException() {
        val ex = mapErrnoToException(ETIMEDOUT, "read")
        assertIs<SocketTimeoutException>(ex)
    }

    @Test
    fun etime_producesSocketTimeoutException() {
        val ex = mapErrnoToException(ETIME, "read")
        assertIs<SocketTimeoutException>(ex)
    }

    @Test
    fun eagain_producesSocketTimeoutException() {
        val ex = mapErrnoToException(EAGAIN, "recv")
        assertIs<SocketTimeoutException>(ex)
    }

    @Test
    fun unknownErrno_producesSocketIOException() {
        val ex = mapErrnoToException(999, "op")
        assertIs<SocketIOException>(ex)
    }

    @Test
    fun message_containsOperationAndErrno() {
        val ex = mapErrnoToException(ECONNREFUSED, "connect")
        assertTrue(ex.message.contains("connect"), "message should contain operation name")
        assertTrue(ex.message.contains("errno="), "message should contain errno")
    }

    // ── throwFromResult wiring tests ──────────────────────────────────
    // These call the actual production entry point that connectWithIoUring,
    // handleReadError, and handleWriteError delegate to, proving the
    // wiring is correct end-to-end with real platform errno constants.

    @Test
    fun throwFromResult_econnrefused() {
        val ex =
            try {
                throwFromResult(-ECONNREFUSED, "connect")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketConnectionException.Refused>(ex)
    }

    @Test
    fun throwFromResult_enetunreach() {
        val ex =
            try {
                throwFromResult(-ENETUNREACH, "connect")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketConnectionException.NetworkUnreachable>(ex)
    }

    @Test
    fun throwFromResult_ehostunreach() {
        val ex =
            try {
                throwFromResult(-EHOSTUNREACH, "connect")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketConnectionException.HostUnreachable>(ex)
    }

    @Test
    fun throwFromResult_econnreset() {
        val ex =
            try {
                throwFromResult(-ECONNRESET, "recv")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketClosedException.ConnectionReset>(ex)
    }

    @Test
    fun throwFromResult_epipe() {
        val ex =
            try {
                throwFromResult(-EPIPE, "send")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketClosedException.BrokenPipe>(ex)
    }

    @Test
    fun throwFromResult_etimedout() {
        val ex =
            try {
                throwFromResult(-ETIMEDOUT, "read")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketTimeoutException>(ex)
    }
}
