package com.ditchoom.socket.http3

import com.ditchoom.socket.http3.codec.Http3StreamId
import com.ditchoom.socket.quic.QuicStreamId

/** The HTTP/3 name of a QUIC stream id: the same 62-bit value, no allocation. */
internal fun QuicStreamId.toHttp3StreamId(): Http3StreamId = Http3StreamId(id)
