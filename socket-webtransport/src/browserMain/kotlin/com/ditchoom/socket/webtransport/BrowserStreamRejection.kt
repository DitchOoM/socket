package com.ditchoom.socket.webtransport

/**
 * What a rejected WHATWG stream read or write of a browser WebTransport stream says about how the stream
 * ended. Each backend's `classifyStreamRejection` builds it from the JS rejection reason:
 *  - a `WebTransportError` with `source === "stream"` and a `streamErrorCode` → [PeerAborted] (W3C
 *    WebTransport: the error a received RESET_STREAM / STOP_SENDING errors the stream with),
 *  - a `DOMException` named `NetworkError` → [PeerAbortedWithoutCode] (Chrome's "The stream was aborted by
 *    the remote server": its network service closed the stream's data pipe — on STOP_SENDING for the send
 *    side, on an end without FIN for the receive side — and the page saw the closed pipe before any
 *    code-carrying notification),
 *  - anything else → [NotPeerAbort].
 */
internal sealed interface BrowserStreamRejection {
    /** The peer aborted the stream with WebTransport application code [errorCode]. */
    data class PeerAborted(
        val errorCode: UInt,
    ) : BrowserStreamRejection

    /** The peer aborted the stream and the browser did not report the code. */
    data object PeerAbortedWithoutCode : BrowserStreamRejection

    /** Not a peer abort: a local abort or cancel, a session close, or a use of an already-closed stream. */
    data object NotPeerAbort : BrowserStreamRejection
}
