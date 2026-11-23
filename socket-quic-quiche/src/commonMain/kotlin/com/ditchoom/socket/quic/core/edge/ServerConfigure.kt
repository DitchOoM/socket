package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.use
import com.ditchoom.socket.quic.ApiQuicConfigCalls
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.applySessionTicketKeys
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.encodeAlpnList

/** Applies the certificate, ALPN, options, handshake rule and session ticket keys to [quicheConfig]. */
internal fun configureServer(
    api: QuicheApi,
    quicheConfig: QuicheConfig,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
    handshake: HandshakeRule,
) {
    nullTerminated(tls.certChainPath, factory).use { api.configLoadCertChainFromPemFile(quicheConfig, addressOf(it)) }
    nullTerminated(tls.privKeyPath, factory).use { api.configLoadPrivKeyFromPemFile(quicheConfig, addressOf(it)) }
    encodeAlpnList(options.alpnProtocols, factory).use { api.configSetApplicationProtos(quicheConfig, addressOf(it), it.remaining()) }
    applyQuicOptions(options.forQuiche(), ApiQuicConfigCalls(api, quicheConfig), QuicRole.Server)
    handshake.applyTo(api, quicheConfig)
    api.applySessionTicketKeys(quicheConfig, tls.sessionTicketKeys, factory)
}
