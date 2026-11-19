package com.ditchoom.socket.quic

/**
 * Decodes the address family of the `sockaddr` at native address [addr]: 4 for AF_INET, 6 for AF_INET6, 0
 * otherwise. The family field's width and offset are the one place `sockaddr` differs between platforms.
 */
internal expect fun readSockAddrFamily(addr: Long): Int
