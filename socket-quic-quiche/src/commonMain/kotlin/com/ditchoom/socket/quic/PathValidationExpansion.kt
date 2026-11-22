package com.ditchoom.socket.quic

/**
 * RFC 9000 §8.2.1: "An endpoint MUST expand datagrams that contain a PATH_CHALLENGE frame to at least
 * the smallest allowed maximum datagram size of 1200 bytes"; §8.2.2 says the same of PATH_RESPONSE.
 * The same 1200 as §14's minimum maximum datagram size, and the same value [QuicOptions] already
 * refuses to let `maxUdpPayloadSize` fall below.
 */
internal const val PATH_VALIDATION_EXPANSION = 1200
